package com.centrale;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import junit.framework.TestCase;

/**
 * Envoi des notes au classement de TomH0999 (github.com/TomH0999/student-ranking).
 *
 * Tout se passe sur cette machine : le « serveur » est {@link FauxSupabase} (127.0.0.1).
 * Aucun test ne contacte le vrai projet Supabase.
 *
 *  1. Tests unitaires : correspondance des matières et coefficients avec le classement, format JSON
 *     des lignes, identifiants stables.
 *  2. Simulation de bout en bout : ajout, modification, suppression, hors-ligne, jeton expiré, RLS.
 *  3. Moyenne : le calcul du classement (supabase/ranking.sql, repris ici en Java) appliqué aux lignes
 *     reçues par le serveur donne, au bit près, la moyenne de l'appli, y compris sur les vraies notes
 *     de ~/.config/centrale-etudiant/evaluations.csv (lues, jamais modifiées).
 */
public class RankingSyncTest extends TestCase {

    static final String EMAIL = "etudiant@example.org";
    static final String MDP = "mot-de-passe-TRES-secret-42";

    private FauxSupabase serveur;
    private String utilisateur;
    private Path dossier;
    private final List<Note.Evaluation> notes = new ArrayList<>();
    private final List<RankingSync> ouverts = new ArrayList<>();

    @Override protected void setUp() throws Exception {
        serveur = new FauxSupabase();
        utilisateur = serveur.creerCompte(EMAIL, MDP);
        dossier = Files.createTempDirectory("classement-test");
    }

    @Override protected void tearDown() throws Exception {
        for (RankingSync s : ouverts) s.arreter();
        serveur.close();
        try (var fichiers = Files.walk(dossier)) {
            for (Path p : fichiers.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    // ---------------------------------------------------------------------
    // Outils
    // ---------------------------------------------------------------------
    private RankingSync.Reglages reglages(String motDePasse) {
        return new RankingSync.Reglages(serveur.url(), serveur.cle, EMAIL, motDePasse);
    }

    /** Synchroniseur branché sur la liste {@link #notes} (copiée à chaque lecture, comme le fichier local). */
    private RankingSync synchro() {
        return synchro(reglages(MDP), "classement_sync.json");
    }

    private RankingSync synchro(RankingSync.Reglages r, String fichierEtat) {
        RankingSync s = new RankingSync(r, dossier.resolve(fichierEtat), () -> {
            synchronized (notes) {
                List<Note.Evaluation> copie = new ArrayList<>();
                for (Note.Evaluation e : notes) copie.add(e.copie());
                return copie;
            }
        });
        ouverts.add(s);
        return s;
    }

    static Note.Evaluation eval(String code, String nom, double note, double bareme, double coef, long creeLe) {
        return new Note.Evaluation(UUID.randomUUID().toString(), code, nom, note, bareme, coef, creeLe, "ADD");
    }

    private void ajouter(Note.Evaluation e) {
        synchronized (notes) { notes.add(e); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(Map<String, Object> ligne) {
        return (Map<String, Object>) ligne.get("data");
    }

    static void egal(String message, Double attendu, Double obtenu) {
        if (attendu == null) {
            assertNull(message, obtenu);
        } else {
            assertNotNull(message + " (attendu " + attendu + ")", obtenu);
            assertEquals(message, Double.doubleToLongBits(attendu), Double.doubleToLongBits(obtenu));
        }
    }

    // ---------------------------------------------------------------------
    // Calcul du classement : reprise de supabase/ranking.sql (fonction ranking_snapshot)
    // ---------------------------------------------------------------------
    /** Liste `subjects` de ranking.sql, dans l'ordre. */
    static final String[] MATIERES_CLASSEMENT = {
        "R1.01", "R1.02", "R1.03", "R1.04", "R1.05", "R1.06", "R1.07", "R1.08", "R1.09", "R1.10", "R1.11", "R1.12",
        "SAE 1.01", "SAE 1.02", "SAE 1.03", "SAE 1.04", "SAE 1.05", "SAE 1.06"};

    /** Table `coeffs` de ranking.sql (= COEFFICIENTS_UE de js/program.js), dans l'ordre sub_ord. */
    static final Object[][] COEFFS_CLASSEMENT = {
        {"UE 1.1", "SAE 1.01", 40, "R1.01", 42, "R1.02", 12, "R1.10", 6},
        {"UE 1.2", "SAE 1.02", 40, "R1.01", 24, "R1.03", 3, "R1.04", 3, "R1.06", 15, "R1.07", 15},
        {"UE 1.3", "SAE 1.03", 40, "R1.03", 21, "R1.04", 21, "R1.10", 12, "R1.11", 6},
        {"UE 1.4", "SAE 1.04", 40, "R1.05", 36, "R1.06", 18, "R1.09", 6},
        {"UE 1.5", "SAE 1.05", 40, "R1.02", 18, "R1.08", 27, "R1.11", 15},
        {"UE 1.6", "SAE 1.06", 40, "R1.02", 5, "R1.08", 11, "R1.09", 11, "R1.10", 11, "R1.11", 11, "R1.12", 11}};

    record Classement(Map<String, Double> matieres, Map<String, Double> ues, Double generale, int notesRetenues) {}

    private record Valide(String id, String code, double cree, double score, double max, double coeff) {}

    /** Moyennes d'un compte, calculées comme le fait la base à partir des lignes de la table items. */
    static Classement classement(List<Map<String, Object>> lignes) {
        // raw + valid : notes vivantes, nombres JSON seulement, matière connue, bornes de bon sens
        List<Valide> valides = new ArrayList<>();
        for (Map<String, Object> l : lignes) {
            if (!"grades".equals(l.get("collection")) || l.get("deleted_at") != null) continue;
            Map<String, Object> d = data(l);
            if (!(d.get("subjectCode") instanceof String code) || !Arrays.asList(MATIERES_CLASSEMENT).contains(code)) continue;
            if (!(d.get("score") instanceof Number s) || !(d.get("max") instanceof Number m) || !(d.get("coeff") instanceof Number c)) continue;
            double score = s.doubleValue(), max = m.doubleValue(), coeff = c.doubleValue();
            if (score < -1000000 || score > 1000000 || max <= 0 || max > 1000000 || coeff < 0 || coeff > 1000000) continue;
            double cree = d.get("createdAt") instanceof Number n ? n.doubleValue() : 0;
            valides.add(new Valide(String.valueOf(l.get("id")), code, cree, score, max, coeff));
        }
        // order by v.created, v.id collate "C"
        valides.sort(Comparator.comparingDouble(Valide::cree).thenComparing(Valide::id));

        // subject_sums + subject_avg
        Map<String, Double> matieres = new LinkedHashMap<>();
        for (String code : MATIERES_CLASSEMENT) {
            Double sommeNotes = null;
            double sommeCoefs = 0;
            for (Valide v : valides) {
                if (!v.code().equals(code)) continue;
                double terme = ((v.score() / v.max()) * 20d) * v.coeff();
                sommeNotes = sommeNotes == null ? terme : sommeNotes + terme;
                sommeCoefs += v.coeff();
            }
            if (sommeNotes != null && sommeCoefs > 0) matieres.put(code, sommeNotes / sommeCoefs);
        }
        // ue_avg
        Map<String, Double> ues = new LinkedHashMap<>();
        for (Object[] ue : COEFFS_CLASSEMENT) {
            Double somme = null;
            double coefs = 0;
            for (int i = 1; i < ue.length; i += 2) {
                Double moyenne = matieres.get((String) ue[i]);
                if (moyenne == null) continue;
                double terme = moyenne * (Integer) ue[i + 1];
                somme = somme == null ? terme : somme + terme;
                coefs += (Integer) ue[i + 1];
            }
            if (somme != null) ues.put((String) ue[0], somme / coefs);
        }
        // general_avg
        Double generale = null;
        if (!ues.isEmpty()) {
            Double somme = null;
            for (double v : ues.values()) somme = somme == null ? v : somme + v;
            generale = somme / (double) ues.size();
        }
        return new Classement(matieres, ues, generale, valides.size());
    }

    /** Le classement (calcul SQL sur les lignes du serveur) doit donner exactement les moyennes de l'appli. */
    private void verifierMemesMoyennes(String contexte) {
        List<Note.Evaluation> locales;
        synchronized (notes) { locales = new ArrayList<>(notes); }
        locales.sort(Comparator.comparingLong((Note.Evaluation e) -> e.creeLe).thenComparing(e -> e.uid));
        Programme.Resultat appli = Programme.calculer(locales);
        Classement c = classement(serveur.lignes(utilisateur));
        assertEquals(contexte + " / nombre de notes", appli.nbEvaluations(), c.notesRetenues());
        for (Programme.Matiere m : Programme.TOUTES) {
            egal(contexte + " / " + m.code(), appli.moyennesMatieres().get(m.code()), c.matieres().get(m.code()));
        }
        for (Programme.Ue ue : Programme.UES) {
            egal(contexte + " / " + ue.code(), appli.moyennesUe().get(ue.code()), c.ues().get(ue.code()));
        }
        egal(contexte + " / générale", appli.generale(), c.generale());
    }

    // =====================================================================
    // 1. TESTS UNITAIRES
    // =====================================================================

    /** Mêmes codes de matières (subjectCode) et mêmes coefficients d'UE que le classement, dans le même ordre. */
    public void testMatieresEtCoefficientsIdentiquesAuClassement() {
        List<String> codes = new ArrayList<>();
        for (Programme.Matiere m : Programme.TOUTES) codes.add(m.code());
        assertEquals(Arrays.asList(MATIERES_CLASSEMENT), codes);

        assertEquals(COEFFS_CLASSEMENT.length, Programme.COEFFICIENTS_UE.size());
        int n = 0;
        for (Map.Entry<String, Map<String, Integer>> ue : Programme.COEFFICIENTS_UE.entrySet()) {
            Object[] attendu = COEFFS_CLASSEMENT[n++];
            assertEquals(attendu[0], ue.getKey());
            List<Object> obtenu = new ArrayList<>();
            for (Map.Entry<String, Integer> e : ue.getValue().entrySet()) { obtenu.add(e.getKey()); obtenu.add(e.getValue()); }
            assertEquals(ue.getKey(), Arrays.asList(attendu).subList(1, attendu.length), obtenu);
        }
        // toute matière proposée par l'appli est acceptée par le classement
        for (Programme.Matiere m : Programme.TOUTES) {
            assertTrue(m.code(), RankingSync.envoyable(new Note.Evaluation("x", m.code(), "DS", 10, 20, 1, 1, "ADD")));
        }
        assertFalse(RankingSync.envoyable(new Note.Evaluation("x", "Sport", "DS", 10, 20, 1, 1, "ADD")));
        assertFalse(RankingSync.envoyable(new Note.Evaluation("x", "R1.01", "DS", 10, 0, 1, 1, "ADD")));
        assertFalse(RankingSync.envoyable(new Note.Evaluation("x", "R1.01", "DS", Double.NaN, 20, 1, 1, "ADD")));
    }

    /** data = {name, subjectCode, score, max, coeff, createdAt}, comme le gestionnaire de notes. */
    public void testFormatDesDonnees() {
        Note.Evaluation e = new Note.Evaluation("u", "R1.01", "DS1", 14.5, 20, 1, 1788220800000L, "ADD");
        assertEquals("{\"name\":\"DS1\",\"subjectCode\":\"R1.01\",\"score\":14.5,\"max\":20,\"coeff\":1,\"createdAt\":1788220800000}",
                RankingSync.donnees(e));

        Note.Evaluation f = new Note.Evaluation("u", "SAE 1.05", "Oral \"final\" ; été\\hiver\n2e ligne", 3.75, 10, 0.25, 1793905816116L, "SYNC");
        Map<String, Object> d = RankingSync.Json.objet(RankingSync.donnees(f));
        assertEquals(List.of("name", "subjectCode", "score", "max", "coeff", "createdAt"), new ArrayList<>(d.keySet()));
        assertEquals("Oral \"final\" ; été\\hiver\n2e ligne", d.get("name"));
        assertEquals("SAE 1.05", d.get("subjectCode"));
        assertEquals(3.75, d.get("score"));
        assertEquals(10L, d.get("max"));
        assertEquals(0.25, d.get("coeff"));
        assertEquals(1793905816116L, d.get("createdAt")); // nombre (millisecondes), pas une chaîne

        // les nombres relus valent exactement les nombres de l'appli
        for (double v : new double[]{0.1, 0.2, 10.005, 13.333, 7.777, 1 / 3.0, 19.99, 1e-7, 123456.789}) {
            Note.Evaluation g = new Note.Evaluation("u", "R1.02", "x", v, 1000, v, 5, "ADD");
            Map<String, Object> dg = RankingSync.Json.objet(RankingSync.donnees(g));
            assertEquals(Double.doubleToLongBits(v), Double.doubleToLongBits(((Number) dg.get("score")).doubleValue()));
            assertEquals(Double.doubleToLongBits(v), Double.doubleToLongBits(((Number) dg.get("coeff")).doubleValue()));
        }

        // nom limité à 120 caractères (comme le gestionnaire), nom vide remplacé
        Note.Evaluation longue = new Note.Evaluation("u", "R1.01", "x".repeat(200), 1, 20, 1, 1, "ADD");
        assertEquals(120, ((String) RankingSync.Json.objet(RankingSync.donnees(longue)).get("name")).length());
        Note.Evaluation sansNom = new Note.Evaluation("u", "R1.01", "  ", 1, 20, 1, 1, "ADD");
        assertEquals("Évaluation", RankingSync.Json.objet(RankingSync.donnees(sansNom)).get("name"));
    }

    public void testFormatDeLaLigne() {
        Note.Evaluation e = new Note.Evaluation("u", "R1.01", "DS1", 14.5, 20, 1, 1788220800000L, "ADD");
        String id = "d08afe49-3abc-47b3-b160-074b9ba162a1";
        String json = RankingSync.ligne("11111111-2222-3333-4444-555555555555", id, RankingSync.donnees(e),
                "2026-11-16T18:00:00Z", RankingSync.PREFIXE_APPAREIL + "abcd1234");
        Map<String, Object> l = RankingSync.Json.objet(json);
        assertEquals(List.of("user_id", "id", "collection", "data", "deleted_at", "client_updated_at", "device_id"),
                new ArrayList<>(l.keySet()));
        assertEquals("11111111-2222-3333-4444-555555555555", l.get("user_id"));
        assertEquals(id, l.get("id"));
        assertEquals("grades", l.get("collection"));
        assertTrue(l.containsKey("deleted_at"));
        assertNull(l.get("deleted_at"));
        assertEquals("2026-11-16T18:00:00Z", l.get("client_updated_at"));
        assertTrue(((String) l.get("device_id")).length() <= 100);
        assertEquals(RankingSync.Json.objet(RankingSync.donnees(e)), l.get("data"));
    }

    /** Même évaluation -> même identifiant de ligne, toujours un UUID valide. */
    public void testIdentifiantsStables() {
        String uuid = "d08afe49-3abc-47b3-b160-074b9ba162a1";
        assertEquals(uuid, RankingSync.idLigne(uuid));
        assertEquals(uuid, RankingSync.idLigne(uuid.toUpperCase()));
        assertEquals(uuid, RankingSync.idLigne(" " + uuid + " "));
        // identifiant des notes converties de l'ancienne version (UUID version 3) : repris tel quel
        assertEquals("7136d2af-1b0f-3e30-81a1-2492656ca2cd", RankingSync.idLigne("7136d2af-1b0f-3e30-81a1-2492656ca2cd"));

        // identifiant qui n'est pas un UUID : UUID calculé, toujours le même, différent pour chaque évaluation
        for (String uid : new String[]{"note-42", "1-2-3-4-5", "", "d08afe49-3abc-47b3-b160-074b9ba162a", "éval/1"}) {
            String id = RankingSync.idLigne(uid);
            assertEquals(id, RankingSync.idLigne(uid));
            assertEquals(id, UUID.fromString(id).toString());
            assertEquals(36, id.length());
        }
        assertFalse(RankingSync.idLigne("note-42").equals(RankingSync.idLigne("note-43")));
        assertEquals(UUID.nameUUIDFromBytes("centrale-etudiant:evaluation:note-42".getBytes(StandardCharsets.UTF_8)).toString(),
                RankingSync.idLigne("note-42"));
    }

    public void testReglages() {
        Map<String, String> c = new HashMap<>();
        assertNull(RankingSync.Reglages.lire(c::get));
        c.put(RankingSync.CLE_URL, "https://exemple.supabase.co/");
        c.put(RankingSync.CLE_API, "sb_publishable_xxx");
        c.put(RankingSync.CLE_EMAIL, " moi@example.org ");
        assertNull("mot de passe absent = désactivé", RankingSync.Reglages.lire(c::get));
        c.put(RankingSync.CLE_MDP, MDP);
        RankingSync.Reglages r = RankingSync.Reglages.lire(c::get);
        assertNotNull(r);
        assertEquals("https://exemple.supabase.co", r.url());
        assertEquals("moi@example.org", r.email());
        // jamais de secret dans un affichage
        assertFalse(r.toString().contains(MDP));
        assertFalse(r.toString().contains("sb_publishable_xxx"));
        assertFalse(r.toString().contains("moi@example.org"));
        // le mot de passe ne part pas en clair sur le réseau
        c.put(RankingSync.CLE_URL, "http://exemple.supabase.co");
        assertNull(RankingSync.Reglages.lire(c::get));
        c.put(RankingSync.CLE_URL, "");
        assertNull(RankingSync.Reglages.lire(c::get));
    }

    public void testDelaisDesNouveauxEssais() {
        assertEquals(15_000, RankingSync.delaiEssai(1));
        assertEquals(30_000, RankingSync.delaiEssai(2));
        assertEquals(60_000, RankingSync.delaiEssai(3));
        assertEquals(600_000, RankingSync.delaiEssai(12));
        assertEquals(600_000, RankingSync.delaiEssai(500));
    }

    /** Réglages absents : la synchronisation est simplement désactivée, rien n'est contacté. */
    public void testDesactiveSansReglages() {
        ajouter(eval("R1.01", "DS1", 12, 20, 1, 1000));
        RankingSync s = synchro(null, "classement_sync.json");
        assertFalse(s.actif());
        assertEquals(RankingSync.Etat.DESACTIVE, s.etat());
        s.planifier();
        RankingSync.Bilan b = s.synchroniser(true);
        assertFalse(b.ok());
        assertFalse(b.reessayable());
        assertEquals(0, serveur.journal.size());
        assertFalse(Files.exists(dossier.resolve("classement_sync.json")));
    }

    // =====================================================================
    // 2. SIMULATION DE BOUT EN BOUT
    // =====================================================================

    public void testAjoutModificationSuppression() {
        RankingSync s = synchro();

        // --- ajout ---
        Note.Evaluation ds = eval("R1.01", "DS1", 14.5, 20, 2, 1_794_000_000_000L);
        ajouter(ds);
        RankingSync.Bilan b = s.synchroniser(false);
        assertTrue(b.message(), b.ok());
        assertEquals(1, b.envoyees());
        assertEquals(1, serveur.items.size());
        Map<String, Object> l = serveur.ligne(utilisateur, ds.uid);
        assertNotNull("ligne créée avec l'identifiant de l'évaluation", l);
        assertEquals("grades", l.get("collection"));
        assertNull(l.get("deleted_at"));
        assertTrue(((String) l.get("device_id")).startsWith(RankingSync.PREFIXE_APPAREIL));
        assertEquals("DS1", data(l).get("name"));
        assertEquals("R1.01", data(l).get("subjectCode"));
        assertEquals(14.5, data(l).get("score"));
        assertEquals(20L, data(l).get("max"));
        assertEquals(2L, data(l).get("coeff"));
        assertEquals(1_794_000_000_000L, data(l).get("createdAt"));
        assertEquals(RankingSync.Etat.A_JOUR, s.etat());
        verifierMemesMoyennes("après ajout");

        // --- modification : même ligne, nouveau contenu ---
        ds.note = 16;
        ds.nom = "DS1 (corrigé)";
        ds.code = "R1.02";
        b = s.synchroniser(false);
        assertTrue(b.ok());
        assertEquals(1, b.envoyees());
        assertEquals("pas de doublon", 1, serveur.items.size());
        l = serveur.ligne(utilisateur, ds.uid);
        assertEquals(16L, data(l).get("score"));
        assertEquals("DS1 (corrigé)", data(l).get("name"));
        assertEquals("R1.02", data(l).get("subjectCode"));
        assertEquals(1_794_000_000_000L, data(l).get("createdAt"));
        assertNull(l.get("deleted_at"));
        verifierMemesMoyennes("après modification");

        // --- deuxième note, puis suppression de la première ---
        Note.Evaluation tp = eval("R1.02", "TP noté", 7, 10, 1, 1_794_000_100_000L);
        ajouter(tp);
        assertEquals(1, s.synchroniser(false).envoyees());
        synchronized (notes) { notes.remove(ds); }
        b = s.synchroniser(false);
        assertTrue(b.ok());
        assertEquals(0, b.envoyees());
        assertEquals(1, b.supprimees());
        assertEquals("suppression logique : la ligne reste", 2, serveur.items.size());
        assertNotNull(serveur.ligne(utilisateur, ds.uid).get("deleted_at"));
        assertNull(serveur.ligne(utilisateur, tp.uid).get("deleted_at"));
        assertEquals(1, serveur.notesVivantes(utilisateur).size());
        verifierMemesMoyennes("après suppression");

        // --- « Annuler » la suppression : la ligne revit ---
        ajouter(ds);
        b = s.synchroniser(false);
        assertEquals(1, b.envoyees());
        assertEquals(2, serveur.items.size());
        assertNull(serveur.ligne(utilisateur, ds.uid).get("deleted_at"));
        verifierMemesMoyennes("après annulation");
    }

    /** Rejouer la synchronisation n'envoie rien ; « Tout resynchroniser » renvoie tout sans doublon. */
    public void testIdempotence() throws Exception {
        for (int i = 0; i < 250; i++) {
            ajouter(eval(MATIERES_CLASSEMENT[i % 18], "Note " + i, (i * 7) % 21, 20, 1 + (i % 3) * 0.5, 1_794_000_000_000L + i));
        }
        RankingSync s = synchro();
        assertEquals(250, s.synchroniser(false).envoyees());
        assertEquals(250, serveur.items.size());
        assertEquals("250 lignes par lots de 100", 3, serveur.ecritures);
        verifierMemesMoyennes("250 notes");

        int requetes = serveur.journal.size();
        RankingSync.Bilan b = s.synchroniser(false);
        assertTrue(b.ok());
        assertEquals(0, b.envoyees());
        assertEquals("rien à envoyer = aucune requête", requetes, serveur.journal.size());

        b = s.synchroniser(true);
        assertTrue(b.ok());
        assertEquals(250, b.envoyees());
        assertEquals(0, b.supprimees());
        assertEquals(250, serveur.items.size());
        assertEquals(250, serveur.notesVivantes(utilisateur).size());

        // après un redémarrage de l'appli (nouvel objet, même fichier d'état) : toujours rien à envoyer
        RankingSync apresRedemarrage = synchro();
        requetes = serveur.journal.size();
        assertEquals(0, apresRedemarrage.synchroniser(false).envoyees());
        assertEquals(requetes, serveur.journal.size());

        // fichier d'état perdu : tout est renvoyé, toujours sans doublon (mêmes identifiants)
        Files.delete(dossier.resolve("classement_sync.json"));
        assertEquals(250, synchro().synchroniser(false).envoyees());
        assertEquals(250, serveur.items.size());
        verifierMemesMoyennes("après renvoi complet");
    }

    /** Hors-ligne : rien n'est perdu, tout part au retour de la connexion. */
    public void testHorsLignePuisRetour() {
        RankingSync s = synchro();
        Note.Evaluation a = eval("R1.05", "DS SQL", 11, 20, 1, 1000);
        ajouter(a);
        assertTrue(s.synchroniser(false).ok());

        serveur.horsLigne = true;
        Note.Evaluation b = eval("R1.06", "DS maths", 9, 20, 1, 2000);
        ajouter(b);
        a.note = 13;
        RankingSync.Bilan bilan = s.synchroniser(false);
        assertFalse(bilan.ok());
        assertTrue("échec passager : nouvel essai prévu", bilan.reessayable());
        assertEquals(RankingSync.Etat.HORS_LIGNE, s.etat());
        assertEquals(1, serveur.items.size());
        assertEquals(11L, data(serveur.ligne(utilisateur, a.uid)).get("score"));

        // encore un changement hors-ligne : suppression de la note jamais envoyée, ajout d'une autre
        synchronized (notes) { notes.remove(b); }
        Note.Evaluation c = eval("SAE 1.01", "Projet", 15, 20, 1, 3000);
        ajouter(c);
        assertFalse(s.synchroniser(false).ok());

        serveur.horsLigne = false;
        serveur.erreurServeur = true; // serveur en panne (503) : passager aussi
        bilan = s.synchroniser(false);
        assertFalse(bilan.ok());
        assertTrue(bilan.reessayable());
        serveur.erreurServeur = false;

        bilan = s.synchroniser(false);
        assertTrue(bilan.message(), bilan.ok());
        assertEquals(2, bilan.envoyees());
        assertEquals(RankingSync.Etat.A_JOUR, s.etat());
        assertEquals(2, serveur.items.size());
        assertEquals(13L, data(serveur.ligne(utilisateur, a.uid)).get("score"));
        assertNull("jamais envoyée, jamais créée", serveur.ligne(utilisateur, b.uid));
        assertNotNull(serveur.ligne(utilisateur, c.uid));
        verifierMemesMoyennes("après retour en ligne");
    }

    /** En arrière-plan : planifier() rend la main tout de suite ; après un échec, un nouvel essai part tout seul. */
    public void testArrierePlanEtNouvelEssaiAutomatique() throws Exception {
        RankingSync s = synchro();
        s.premierEssaiMs = 100;
        serveur.horsLigne = true;
        Note.Evaluation a = eval("R1.10", "Oral", 12, 16, 0.5, 1000);
        ajouter(a);

        long debut = System.nanoTime();
        s.planifier();
        s.planifier();
        s.planifier();
        assertTrue("planifier() ne doit pas attendre le réseau", (System.nanoTime() - debut) / 1_000_000 < 200);

        attendre(() -> serveur.coupures >= 2); // au moins un essai puis un nouvel essai, sans rien demander
        assertEquals(0, serveur.items.size());
        serveur.horsLigne = false;
        attendre(() -> serveur.ligne(utilisateur, a.uid) != null);
        attendre(() -> s.etat() == RankingSync.Etat.A_JOUR);
        verifierMemesMoyennes("arrière-plan");
    }

    private static void attendre(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long fin = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            assertTrue("condition non atteinte en 15 s", System.currentTimeMillis() < fin);
            Thread.sleep(20);
        }
    }

    /** Jeton expiré : rafraîchi (ou reconnexion) sans rien perdre. */
    public void testJetonExpire() throws Exception {
        RankingSync s = synchro();
        ajouter(eval("R1.01", "DS1", 10, 20, 1, 1000));
        assertTrue(s.synchroniser(false).ok());
        assertEquals(1, serveur.connexions);

        // jeton encore valable : pas de nouvelle connexion
        ajouter(eval("R1.01", "DS2", 12, 20, 1, 2000));
        assertTrue(s.synchroniser(false).ok());
        assertEquals(1, serveur.connexions);
        assertEquals(0, serveur.rafraichis);

        // le serveur refuse le jeton (401) : rafraîchissement, puis la requête est rejouée
        serveur.expirerJetons();
        ajouter(eval("R1.01", "DS3", 14, 20, 1, 3000));
        RankingSync.Bilan b = s.synchroniser(false);
        assertTrue(b.message(), b.ok());
        assertEquals(1, serveur.rafraichis);
        assertEquals(1, serveur.connexions);
        assertEquals(3, serveur.notesVivantes(utilisateur).size());

        // jeton de courte durée (2 s) : renouvelé de lui-même à mi-vie, avant d'être refusé
        serveur.dureeJetonS = 2;
        serveur.expirerJetons();
        ajouter(eval("R1.01", "DS4", 15, 20, 1, 4000));
        assertTrue(s.synchroniser(false).ok());
        int avant = serveur.rafraichis;
        Thread.sleep(1100);
        ajouter(eval("R1.01", "DS5", 16, 20, 1, 5000));
        assertTrue(s.synchroniser(false).ok());
        assertEquals(avant + 1, serveur.rafraichis);
        assertEquals(5, serveur.notesVivantes(utilisateur).size());
        verifierMemesMoyennes("jetons");
    }

    /**
     * RLS et « Tout resynchroniser » : seules les lignes du compte sont touchées ; les lignes écrites par
     * l'appli et absentes ici sont retirées ; les notes saisies ailleurs (gestionnaire web) et les autres
     * collections (devoirs, réglages) ne sont jamais supprimées, seulement comptées.
     */
    public void testAutresComptesEtNotesSaisiesAilleurs() {
        String autre = serveur.creerCompte("autre@example.org", "autre-mot-de-passe");
        String idAutre = UUID.randomUUID().toString();
        serveur.inserer(autre, idAutre, "grades", new LinkedHashMap<>(Map.of("subjectCode", "R1.01", "score", 20L, "max", 20L, "coeff", 1L)), "navigateur");

        String idWeb = UUID.randomUUID().toString(), idOrpheline = UUID.randomUUID().toString(), idDevoir = UUID.randomUUID().toString();
        serveur.inserer(utilisateur, idWeb, "grades", new LinkedHashMap<>(Map.of("subjectCode", "R1.03", "score", 8L, "max", 20L, "coeff", 1L)), "navigateur-web");
        serveur.inserer(utilisateur, idOrpheline, "grades", new LinkedHashMap<>(Map.of("subjectCode", "R1.04", "score", 2L, "max", 20L, "coeff", 1L)),
                RankingSync.PREFIXE_APPAREIL + "ancien-pc");
        serveur.inserer(utilisateur, idDevoir, "homework", new LinkedHashMap<>(Map.of("title", "Rendre le TP")), "navigateur-web");

        RankingSync s = synchro();
        Note.Evaluation ds = eval("R1.01", "DS1", 12, 20, 1, 1000);
        ajouter(ds);

        // synchronisation normale : n'écrit que sa propre ligne
        assertTrue(s.synchroniser(false).ok());
        assertNull(serveur.ligne(utilisateur, idOrpheline).get("deleted_at"));

        RankingSync.Bilan b = s.synchroniser(true);
        assertTrue(b.message(), b.ok());
        assertEquals(1, b.envoyees());
        assertEquals("ligne de l'appli absente ici : retirée", 1, b.supprimees());
        assertEquals("note saisie dans le gestionnaire web : signalée, pas supprimée", 1, b.ailleurs());
        assertNotNull(serveur.ligne(utilisateur, idOrpheline).get("deleted_at"));
        assertNull(serveur.ligne(utilisateur, idWeb).get("deleted_at"));
        assertNull(serveur.ligne(utilisateur, idDevoir).get("deleted_at"));
        assertNull(serveur.ligne(utilisateur, ds.uid).get("deleted_at"));

        // l'autre compte : intact, et aucune ligne créée chez lui
        assertEquals(1, serveur.lignes(autre).size());
        assertNull(serveur.ligne(autre, idAutre).get("deleted_at"));
        assertEquals(20L, data(serveur.ligne(autre, idAutre)).get("score"));
        for (Map<String, Object> l : serveur.items) {
            if (!idAutre.equals(l.get("id"))) assertEquals(utilisateur, l.get("user_id"));
        }
    }

    /** Mot de passe refusé : rien n'est envoyé, pas d'essais en boucle, et aucun secret dans le journal. */
    public void testConnexionRefuseeEtSecretsJamaisJournalises() throws Exception {
        PrintStream err = System.err, out = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream flux = new PrintStream(capture, true, StandardCharsets.UTF_8);
        String mauvais = "mauvais-mot-de-passe-SECRET";
        RankingSync.Bilan refuse, accepte;
        try {
            System.setErr(flux);
            System.setOut(flux);
            ajouter(eval("R1.01", "DS1", 12, 20, 1, 1000));
            refuse = synchro(reglages(mauvais), "etat-refuse.json").synchroniser(false);
            accepte = synchro().synchroniser(true);
        } finally {
            System.setErr(err);
            System.setOut(out);
        }
        assertFalse(refuse.ok());
        assertFalse("identifiants refusés : pas de nouvel essai automatique", refuse.reessayable());
        assertTrue(refuse.message(), refuse.message().contains("invalid_credentials"));
        assertTrue(accepte.message(), accepte.ok());
        assertEquals(1, serveur.items.size());

        String journal = capture.toString(StandardCharsets.UTF_8);
        assertTrue("l'échec est journalisé", journal.contains("Classement"));
        String etat = Files.readString(dossier.resolve("classement_sync.json"));
        List<String> secrets = new ArrayList<>(List.of(MDP, mauvais, EMAIL, serveur.cle));
        for (String requete : serveur.journal) {
            // jetons d'accès et de rafraîchissement délivrés par le serveur
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(acces|rafraichissement)-[0-9a-f-]{36}").matcher(requete);
            while (m.find()) secrets.add(m.group());
        }
        assertTrue(secrets.size() > 4);
        for (String secret : secrets) {
            assertFalse("secret dans le journal", journal.contains(secret));
            assertFalse("secret dans le fichier d'état", etat.contains(secret));
            assertFalse(refuse.message().contains(secret));
        }
        // le mot de passe n'est envoyé qu'à /auth/v1/token, jamais ailleurs
        for (String requete : serveur.journal) {
            if (requete.contains(MDP) || requete.contains(mauvais)) assertTrue(requete, requete.startsWith("POST /auth/v1/token?grant_type=password"));
        }
    }

    // =====================================================================
    // 3. VRAIES NOTES (lecture seule) : moyenne du classement = moyenne de l'appli
    // =====================================================================

    /** evaluations.csv de l'utilisateur ; absent (autre machine, intégration continue) = test sans objet. */
    static Path fichierReel() {
        String force = System.getProperty("centrale.evaluations");
        if (force != null) return Paths.get(force);
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            return (appdata != null ? Paths.get(appdata) : Paths.get(home, "AppData", "Roaming")).resolve("CentraleEtudiant").resolve("evaluations.csv");
        }
        if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "CentraleEtudiant", "evaluations.csv");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank() ? Paths.get(xdg) : Paths.get(home, ".config")).resolve("centrale-etudiant").resolve("evaluations.csv");
    }

    public void testVraiesNotesMemeMoyenneQueLeClassement() throws Exception {
        Path fichier = fichierReel();
        if (!Files.isReadable(fichier)) {
            System.out.println("[classement] " + fichier + " absent : simulation sur les vraies notes ignorée");
            return;
        }
        byte[] avant = Files.readAllBytes(fichier);

        // même lecture que Note.Stockage (uid;code;note;bareme;coef;cree_le;statut;nom), sans rien écrire
        for (String ligne : new String(avant, StandardCharsets.UTF_8).split("\n")) {
            String[] p = ligne.strip().split(";", 8);
            if (p.length != 8 || p[0].equals("uid") || p[6].equals("DEL")) continue;
            ajouter(new Note.Evaluation(p[0], p[1], p[7].replace("\\n", "\n").replace("\\\\", "\\"), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Double.parseDouble(p[4]), Long.parseLong(p[5]), p[6]));
        }
        int total = notes.size();

        RankingSync s = synchro();
        RankingSync.Bilan b = s.synchroniser(false);
        assertTrue(b.message(), b.ok());
        assertEquals(total, b.envoyees());
        assertEquals(total, serveur.notesVivantes(utilisateur).size());
        verifierMemesMoyennes("vraies notes");

        List<Note.Evaluation> triees = new ArrayList<>(notes);
        triees.sort(Comparator.comparingLong((Note.Evaluation e) -> e.creeLe).thenComparing(e -> e.uid));
        Programme.Resultat appli = Programme.calculer(triees);
        Classement c = classement(serveur.lignes(utilisateur));
        System.out.println("[classement] vraies notes : " + total + " évaluation(s) envoyée(s) au faux serveur en "
                + serveur.ecritures + " requête(s) d'écriture");
        System.out.println("[classement]   moyenne générale  appli = " + appli.generale() + "   classement = " + c.generale());
        for (Programme.Ue ue : Programme.UES) {
            System.out.println("[classement]   " + ue.code() + "            appli = " + appli.moyennesUe().get(ue.code())
                    + "   classement = " + c.ues().get(ue.code()));
        }
        System.out.println("[classement]   matières notées : " + c.matieres().size() + "/18, UE calculées : " + c.ues().size() + "/6");

        // puis la vie normale sur ces notes : modification, suppression, ajout -> toujours la même moyenne
        if (total > 0) {
            Note.Evaluation premiere = notes.get(0);
            premiere.note = premiere.bareme / 2;
            assertEquals(1, s.synchroniser(false).envoyees());
            verifierMemesMoyennes("vraies notes, une modifiée");
            synchronized (notes) { notes.remove(premiere); }
            assertEquals(1, s.synchroniser(false).supprimees());
            assertEquals(total - 1, serveur.notesVivantes(utilisateur).size());
            verifierMemesMoyennes("vraies notes, une supprimée");
        }
        ajouter(eval("SAE 1.02", "Soutenance", 13.25, 20, 1.5, System.currentTimeMillis()));
        assertEquals(1, s.synchroniser(false).envoyees());
        verifierMemesMoyennes("vraies notes, une ajoutée");

        assertTrue("evaluations.csv ne doit pas être modifié", Arrays.equals(avant, Files.readAllBytes(fichier)));
    }
}
