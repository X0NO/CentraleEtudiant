package com.centrale;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * Envoi des notes vers le classement de TomH0999 (github.com/TomH0999/student-ranking).
 *
 * Le classement lit la table Supabase {@code items} du « gestionnaire de notes » : une ligne par note,
 * {@code collection = 'grades'}, contenu dans {@code data} (jsonb) :
 * {@code name, subjectCode, score, max, coeff, createdAt}. Cette classe y recopie les évaluations
 * de evaluations.csv, exactement dans ce format, pour que la moyenne du classement soit la même
 * que celle de l'appli.
 *
 * Fonctionnement :
 *  - envoi dans un seul sens (appli -> classement), toujours sur un fil à part : l'interface n'attend jamais ;
 *  - un fichier d'état (classement_sync.json) retient ce qui est déjà sur le serveur. Chaque synchronisation
 *    compare les notes locales à cet état et n'envoie que la différence : notes nouvelles ou modifiées
 *    (upsert), notes disparues (suppression logique, {@code deleted_at}). Rejouer une synchronisation
 *    n'envoie rien et ne crée jamais de doublon (identifiant de ligne = identifiant de l'évaluation) ;
 *  - hors-ligne : rien n'est perdu, l'état n'est pas modifié et un nouvel essai est programmé
 *    (15 s, 30 s, 1 min... 10 min au plus), puis à chaque démarrage ;
 *  - réglages dans config.properties : RANKING_SUPABASE_URL, RANKING_SUPABASE_KEY (clé PUBLIQUE),
 *    RANKING_EMAIL, RANKING_PASSWORD. S'il en manque un, la synchronisation est désactivée ;
 *  - le mot de passe et les jetons ne sont jamais écrits dans un fichier ni dans le journal.
 */
final class RankingSync {

    static final String CLE_URL   = "RANKING_SUPABASE_URL";
    static final String CLE_API   = "RANKING_SUPABASE_KEY";
    static final String CLE_EMAIL = "RANKING_EMAIL";
    static final String CLE_MDP   = "RANKING_PASSWORD";

    static final String COLLECTION = "grades";
    /** Début du device_id des lignes écrites par cette appli (colonne limitée à 100 caractères). */
    static final String PREFIXE_APPAREIL = "centrale-etudiant-java-";
    /** Longueur maximale d'un nom d'évaluation dans le gestionnaire de notes. */
    static final int NOM_MAX = 120;
    /** Lignes par requête. */
    static final int LOT = 100;
    /** Attente après une modification avant d'envoyer (plusieurs clics rapprochés = un seul envoi). */
    static final long DELAI_MS = 400;
    static final long PREMIER_ESSAI_MS = 15_000;
    static final long ESSAI_MAX_MS = 10 * 60_000;

    private static final JsonFactory JSON = new JsonFactory();

    // =====================================================================
    // RÉGLAGES
    // =====================================================================
    record Reglages(String url, String cle, String email, String motDePasse) {

        /** null si un réglage manque ou si l'adresse n'est pas en https (le mot de passe ne part jamais en clair). */
        static Reglages lire(Function<String, String> source) {
            String url = propre(source.apply(CLE_URL));
            String cle = propre(source.apply(CLE_API));
            String email = propre(source.apply(CLE_EMAIL));
            String mdp = source.apply(CLE_MDP);
            if (url == null || cle == null || email == null || mdp == null || mdp.isBlank()) return null;
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (!adresseSure(url)) {
                System.err.println("Classement : " + CLE_URL + " doit commencer par https:// (synchronisation désactivée)");
                return null;
            }
            return new Reglages(url, cle, email, mdp);
        }

        private static String propre(String s) {
            return (s == null || s.isBlank()) ? null : s.trim();
        }

        static boolean adresseSure(String url) {
            String u = url.toLowerCase(Locale.ROOT);
            return u.startsWith("https://") || u.startsWith("http://127.0.0.1:") || u.startsWith("http://localhost:");
        }

        /** Empreinte du compte (pas un secret) : si on change de compte, l'état local repart de zéro. */
        String compte() {
            return empreinte(url + "|" + email.toLowerCase(Locale.ROOT)).substring(0, 16);
        }

        /** Jamais de secret dans un message ou un journal. */
        @Override public String toString() {
            return "Reglages[url=" + url + ", cle=***, email=***, motDePasse=***]";
        }
    }

    // =====================================================================
    // FORMAT DES LIGNES (fonctions pures, testées dans RankingSyncTest)
    // =====================================================================

    /**
     * Identifiant (UUID) de la ligne {@code items} d'une évaluation. Toujours le même pour la même
     * évaluation : renvoyer une note la met à jour, sans doublon.
     * Les identifiants de l'appli sont déjà des UUID : repris tels quels (le classement départage les notes
     * créées au même instant par identifiant, donc même ordre de calcul que l'appli). Sinon, UUID calculé
     * à partir de l'identifiant.
     */
    static String idLigne(String uid) {
        String u = uid == null ? "" : uid.trim().toLowerCase(Locale.ROOT);
        if (u.length() == 36) {
            try {
                if (UUID.fromString(u).toString().equals(u)) return u;
            } catch (IllegalArgumentException ignored) {
                // pas un UUID : identifiant calculé ci-dessous
            }
        }
        return UUID.nameUUIDFromBytes(("centrale-etudiant:evaluation:" + uid).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** true si l'évaluation peut compter dans le classement (matière du programme, nombres valides). */
    static boolean envoyable(Note.Evaluation e) {
        return Programme.existe(e.code)
                && fini(e.note) && fini(e.bareme) && fini(e.coef)
                && e.bareme > 0 && e.coef >= 0;
    }

    private static boolean fini(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /**
     * Contenu {@code data} d'une note, au format du gestionnaire de notes :
     * {@code {"name":"DS1","subjectCode":"R1.01","score":14.5,"max":20,"coeff":1,"createdAt":1788220800000}}.
     */
    static String donnees(Note.Evaluation e) {
        try {
            StringWriter w = new StringWriter();
            try (JsonGenerator g = JSON.createGenerator(w)) {
                g.writeStartObject();
                String nom = (e.nom == null || e.nom.isBlank()) ? "Évaluation" : e.nom.trim();
                g.writeStringField("name", nom.length() > NOM_MAX ? nom.substring(0, NOM_MAX) : nom);
                g.writeStringField("subjectCode", e.code);
                nombre(g, "score", e.note);
                nombre(g, "max", e.bareme);
                nombre(g, "coeff", e.coef);
                g.writeNumberField("createdAt", e.creeLe);
                g.writeEndObject();
            }
            return w.toString();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 20.0 -> 20 (comme JSON.stringify en JavaScript), 14.5 -> 14.5 : même nombre, lu à l'identique. */
    private static void nombre(JsonGenerator g, String champ, double v) throws IOException {
        if (v == Math.rint(v) && Math.abs(v) < 9.007199254740992E15) g.writeNumberField(champ, (long) v);
        else g.writeNumberField(champ, v);
    }

    /** Ligne complète de la table {@code items} (note vivante : deleted_at = null). */
    static String ligne(String userId, String id, String donneesJson, String horodatage, String appareil) {
        try {
            StringWriter w = new StringWriter();
            try (JsonGenerator g = JSON.createGenerator(w)) {
                g.writeStartObject();
                g.writeStringField("user_id", userId);
                g.writeStringField("id", id);
                g.writeStringField("collection", COLLECTION);
                g.writeFieldName("data");
                g.writeRawValue(donneesJson);
                g.writeNullField("deleted_at");
                g.writeStringField("client_updated_at", horodatage);
                g.writeStringField("device_id", appareil);
                g.writeEndObject();
            }
            return w.toString();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String empreinte(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Délai avant le n-ième nouvel essai : 15 s, 30 s, 1 min, 2 min... plafonné à 10 min. */
    static long delaiEssai(int echecs) {
        return delaiEssai(echecs, PREMIER_ESSAI_MS);
    }

    static long delaiEssai(int echecs, long premier) {
        long d = premier;
        for (int i = 1; i < echecs && d < ESSAI_MAX_MS; i++) d *= 2;
        return Math.min(d, ESSAI_MAX_MS);
    }

    // =====================================================================
    // JSON (lecture minimale avec jackson-core, déjà dans le projet)
    // =====================================================================
    static final class Json {
        private Json() {}

        /** Objet -> Map, tableau -> List, nombres -> Long ou Double, null si le texte n'est pas du JSON. */
        static Object lire(String texte) {
            if (texte == null || texte.isBlank()) return null;
            try (JsonParser p = JSON.createParser(texte)) {
                p.nextToken();
                return valeur(p);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> objet(String texte) {
            return lire(texte) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        private static Object valeur(JsonParser p) throws IOException {
            JsonToken t = p.currentToken();
            if (t == null) return null;
            switch (t) {
                case START_OBJECT: {
                    Map<String, Object> m = new LinkedHashMap<>();
                    while (p.nextToken() != JsonToken.END_OBJECT) {
                        String cle = p.currentName();
                        p.nextToken();
                        m.put(cle, valeur(p));
                    }
                    return m;
                }
                case START_ARRAY: {
                    List<Object> l = new ArrayList<>();
                    while (p.nextToken() != JsonToken.END_ARRAY) l.add(valeur(p));
                    return l;
                }
                case VALUE_STRING: return p.getText();
                case VALUE_NUMBER_INT: return p.getLongValue();
                case VALUE_NUMBER_FLOAT: return p.getDoubleValue();
                case VALUE_TRUE: return Boolean.TRUE;
                case VALUE_FALSE: return Boolean.FALSE;
                default: return null;
            }
        }

        static String texte(String s) {
            try {
                StringWriter w = new StringWriter();
                try (JsonGenerator g = JSON.createGenerator(w)) {
                    g.writeString(s);
                }
                return w.toString();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    // =====================================================================
    // RÉSULTAT / ÉTAT
    // =====================================================================
    enum Etat { DESACTIVE, INCONNU, EN_COURS, A_JOUR, HORS_LIGNE, ERREUR }

    /**
     * @param reessayable échec passager (réseau, serveur) : un nouvel essai est utile
     * @param ailleurs    notes vivantes du compte qui ne viennent pas de cette appli (saisies dans le
     *                    gestionnaire web) : elles comptent aussi dans le classement (connu après « Tout resynchroniser »)
     */
    record Bilan(boolean ok, boolean reessayable, int envoyees, int supprimees, int ailleurs, String message) {}

    /** Échec d'un échange avec le serveur. Le message ne contient jamais de secret. */
    static final class Echec extends Exception {
        final boolean reessayable;

        Echec(String message, boolean reessayable) {
            super(message);
            this.reessayable = reessayable;
        }
    }

    private record Reponse(int statut, String corps) {}

    // =====================================================================
    // INSTANCE
    // =====================================================================
    private final Reglages reglages;
    private final Path fichierEtat;
    /** Notes à publier (visibles, non supprimées) ; null = inconnu pour l'instant, on ne touche à rien. */
    private final Supplier<List<Note.Evaluation>> source;
    private final HttpClient http;
    private final ScheduledExecutorService fil;
    private final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();

    private volatile Etat etat;
    private volatile String dernierMessage = "";
    private ScheduledFuture<?> prevue;
    private int echecs = 0;
    /** Délai du premier nouvel essai après un échec passager (raccourci dans les tests). */
    long premierEssaiMs = PREMIER_ESSAI_MS;

    // session (en mémoire seulement)
    private String jeton, jetonRafraichissement, idUtilisateur;
    private long expireLe = 0;

    // état local : ce qui est sur le serveur
    private String appareil;
    private final Map<String, String> lignes = new TreeMap<>(); // id -> empreinte du contenu envoyé
    private boolean etatCharge = false;

    RankingSync(Reglages reglages, Path fichierEtat, Supplier<List<Note.Evaluation>> source) {
        this.reglages = reglages;
        this.fichierEtat = fichierEtat;
        this.source = source;
        this.etat = reglages == null ? Etat.DESACTIVE : Etat.INCONNU;
        this.http = reglages == null ? null : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.fil = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "synchro-classement");
            t.setDaemon(true);
            return t;
        });
    }

    boolean actif() {
        return reglages != null;
    }

    Etat etat() {
        return etat;
    }

    String dernierMessage() {
        return dernierMessage;
    }

    /** Appelé dans le fil de l'interface à chaque changement d'état. */
    void auChangement(Runnable r) {
        ecouteurs.add(r);
    }

    private void changerEtat(Etat nouveau) {
        if (etat == nouveau) return;
        etat = nouveau;
        for (Runnable r : ecouteurs) SwingUtilities.invokeLater(r);
    }

    void arreter() {
        fil.shutdownNow();
    }

    // ---------------------------------------------------------------------
    // Planification (ne bloque jamais l'appelant)
    // ---------------------------------------------------------------------

    /** Demande une synchronisation dans un instant, sur le fil de fond. Sans effet si désactivée. */
    void planifier() {
        planifier(DELAI_MS);
    }

    private synchronized void planifier(long delaiMs) {
        if (!actif() || fil.isShutdown()) return;
        if (prevue != null) prevue.cancel(false);
        prevue = fil.schedule(this::tour, delaiMs, TimeUnit.MILLISECONDS);
    }

    private void tour() {
        Bilan b;
        try {
            b = synchroniser(false);
        } catch (Throwable t) {
            System.err.println("Classement : synchronisation impossible (" + t.getClass().getSimpleName() + ")");
            return;
        }
        if (!b.ok() && b.reessayable()) {
            int n;
            synchronized (this) { n = ++echecs; }
            planifier(delaiEssai(n, premierEssaiMs));
        } else {
            synchronized (this) { echecs = 0; }
        }
    }

    /** « Tout resynchroniser » : renvoie toutes les notes ; {@code ensuite} reçoit le bilan dans le fil de l'interface. */
    void toutResynchroniser(Consumer<Bilan> ensuite) {
        if (!actif() || fil.isShutdown()) {
            if (ensuite != null) ensuite.accept(new Bilan(false, false, 0, 0, 0, "Classement non configuré"));
            return;
        }
        fil.submit(() -> {
            Bilan b;
            try {
                b = synchroniser(true);
            } catch (Throwable t) {
                b = new Bilan(false, false, 0, 0, 0, "Erreur inattendue (" + t.getClass().getSimpleName() + ")");
            }
            Bilan bilan = b;
            if (ensuite != null) SwingUtilities.invokeLater(() -> ensuite.accept(bilan));
        });
    }

    // ---------------------------------------------------------------------
    // Synchronisation (à appeler hors de l'interface)
    // ---------------------------------------------------------------------

    /**
     * Aligne le serveur sur les notes locales.
     * @param complet true = renvoie toutes les notes, même celles déjà envoyées, et supprime du serveur
     *                les lignes écrites par cette appli qui n'existent plus ici
     */
    synchronized Bilan synchroniser(boolean complet) {
        if (!actif()) return new Bilan(false, false, 0, 0, 0, "Classement non configuré");

        List<Note.Evaluation> notes;
        try {
            notes = source.get();
        } catch (RuntimeException e) {
            return echec(new Echec("lecture des notes locales impossible", false));
        }
        if (notes == null) return new Bilan(true, false, 0, 0, 0, "Aucune note locale");

        chargerEtat();

        // Ce qui doit être sur le serveur : id -> data
        Map<String, String> voulues = new LinkedHashMap<>();
        for (Note.Evaluation e : notes) {
            if (!envoyable(e)) {
                System.err.println("Classement : évaluation ignorée (matière hors programme ou valeur invalide) : " + e.code);
                continue;
            }
            voulues.put(idLigne(e.uid), donnees(e));
        }

        List<String> aEnvoyer = new ArrayList<>();
        for (Map.Entry<String, String> v : voulues.entrySet()) {
            if (complet || !empreinte(v.getValue()).equals(lignes.get(v.getKey()))) aEnvoyer.add(v.getKey());
        }
        List<String> aSupprimer = new ArrayList<>();
        for (String id : lignes.keySet()) if (!voulues.containsKey(id)) aSupprimer.add(id);

        if (!complet && aEnvoyer.isEmpty() && aSupprimer.isEmpty()) {
            changerEtat(Etat.A_JOUR);
            return new Bilan(true, false, 0, 0, 0, "Déjà à jour");
        }

        changerEtat(Etat.EN_COURS);
        int envoyees = 0, supprimees = 0, ailleurs = 0;
        try {
            ouvrirSession();

            if (complet) {
                // Lignes vivantes du compte : celles écrites par cette appli et absentes ici sont à supprimer
                for (Map<String, Object> l : lignesVivantes()) {
                    String id = String.valueOf(l.get("id")).toLowerCase(Locale.ROOT);
                    if (voulues.containsKey(id)) continue;
                    Object ap = l.get("device_id");
                    boolean aNous = lignes.containsKey(id) || (ap instanceof String s && s.startsWith(PREFIXE_APPAREIL));
                    if (!aNous) ailleurs++;
                    else if (!aSupprimer.contains(id)) aSupprimer.add(id);
                }
            }

            for (int i = 0; i < aEnvoyer.size(); i += LOT) {
                List<String> lot = aEnvoyer.subList(i, Math.min(i + LOT, aEnvoyer.size()));
                String maintenant = Instant.now().toString();
                StringBuilder corps = new StringBuilder("[");
                for (String id : lot) {
                    if (corps.length() > 1) corps.append(',');
                    corps.append(ligne(idUtilisateur, id, voulues.get(id), maintenant, appareil));
                }
                corps.append(']');
                Reponse r = authentifie("POST", "/rest/v1/items?on_conflict=user_id,id", corps.toString(),
                        "resolution=merge-duplicates,return=minimal");
                verifier(r, "envoi des notes");
                for (String id : lot) lignes.put(id, empreinte(voulues.get(id)));
                envoyees += lot.size();
                enregistrerEtat();
            }

            for (int i = 0; i < aSupprimer.size(); i += LOT) {
                List<String> lot = aSupprimer.subList(i, Math.min(i + LOT, aSupprimer.size()));
                String maintenant = Instant.now().toString();
                String corps = "{\"deleted_at\":" + Json.texte(maintenant) + ",\"client_updated_at\":" + Json.texte(maintenant)
                        + ",\"device_id\":" + Json.texte(appareil) + "}";
                Reponse r = authentifie("PATCH", "/rest/v1/items?user_id=eq." + idUtilisateur + "&collection=eq." + COLLECTION
                        + "&id=in.(" + String.join(",", lot) + ")", corps, "return=minimal");
                verifier(r, "suppression de notes");
                for (String id : lot) lignes.remove(id);
                supprimees += lot.size();
                enregistrerEtat();
            }
        } catch (Echec e) {
            enregistrerEtat();
            return echec(e);
        }

        changerEtat(Etat.A_JOUR);
        dernierMessage = envoyees + " note(s) envoyée(s), " + supprimees + " supprimée(s)";
        System.out.println("Classement : " + dernierMessage
                + (ailleurs > 0 ? " ; " + ailleurs + " note(s) saisie(s) ailleurs restent sur le compte" : ""));
        return new Bilan(true, false, envoyees, supprimees, ailleurs, dernierMessage);
    }

    private Bilan echec(Echec e) {
        dernierMessage = e.getMessage();
        changerEtat(e.reessayable ? Etat.HORS_LIGNE : Etat.ERREUR);
        System.err.println("Classement : " + e.getMessage()
                + (e.reessayable ? " (les notes restent en attente, nouvel essai automatique)" : ""));
        return new Bilan(false, e.reessayable, 0, 0, 0, e.getMessage());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lignesVivantes() throws Echec {
        List<Map<String, Object>> toutes = new ArrayList<>();
        int page = 1000;
        for (int debut = 0; ; debut += page) {
            Reponse r = authentifie("GET", "/rest/v1/items?select=id,device_id&collection=eq." + COLLECTION
                    + "&deleted_at=is.null&order=id&limit=" + page + "&offset=" + debut, null, null);
            verifier(r, "lecture des notes du compte");
            if (!(Json.lire(r.corps()) instanceof List<?> l)) throw new Echec("réponse du serveur illisible", true);
            for (Object o : l) if (o instanceof Map<?, ?> m) toutes.add((Map<String, Object>) m);
            if (l.size() < page) return toutes;
        }
    }

    // ---------------------------------------------------------------------
    // Session : connexion par mot de passe, rafraîchissement du jeton
    // ---------------------------------------------------------------------
    private void ouvrirSession() throws Echec {
        if (jeton != null && System.currentTimeMillis() < expireLe) return;
        if (jetonRafraichissement != null) {
            Reponse r = envoyer("POST", "/auth/v1/token?grant_type=refresh_token",
                    "{\"refresh_token\":" + Json.texte(jetonRafraichissement) + "}", null, null);
            if (r.statut() == 200 && lireSession(r.corps())) return;
            if (r.statut() >= 500 || r.statut() == 429) throw new Echec("serveur du classement indisponible (" + r.statut() + ")", true);
            jetonRafraichissement = null; // jeton refusé : on se reconnecte avec le mot de passe
        }
        Reponse r = envoyer("POST", "/auth/v1/token?grant_type=password",
                "{\"email\":" + Json.texte(reglages.email()) + ",\"password\":" + Json.texte(reglages.motDePasse()) + "}", null, null);
        if (r.statut() == 200 && lireSession(r.corps())) return;
        jeton = null;
        if (r.statut() >= 500 || r.statut() == 429) throw new Echec("serveur du classement indisponible (" + r.statut() + ")", true);
        throw new Echec("connexion au classement refusée (" + r.statut() + " " + codeErreur(r.corps())
                + ") : vérifie " + CLE_EMAIL + ", " + CLE_MDP + " et " + CLE_API + " dans config.properties", false);
    }

    private boolean lireSession(String corps) {
        Map<String, Object> o = Json.objet(corps);
        if (!(o.get("access_token") instanceof String a) || a.isBlank()) return false;
        if (!(o.get("user") instanceof Map<?, ?> u) || !(u.get("id") instanceof String id)) return false;
        jeton = a;
        idUtilisateur = id.toLowerCase(Locale.ROOT);
        jetonRafraichissement = o.get("refresh_token") instanceof String rf && !rf.isBlank() ? rf : null;
        long duree = o.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        // renouvelé un peu avant la fin (une minute, ou la moitié de sa durée s'il est très court)
        expireLe = System.currentTimeMillis() + duree * 1000 - Math.min(60_000, duree * 500);
        return true;
    }

    /** Requête avec le jeton ; s'il est refusé (expiré), on en redemande un et on recommence une fois. */
    private Reponse authentifie(String methode, String chemin, String corps, String prefer) throws Echec {
        ouvrirSession();
        Reponse r = envoyer(methode, chemin, corps, jeton, prefer);
        if (r.statut() == 401) {
            jeton = null;
            ouvrirSession();
            r = envoyer(methode, chemin, corps, jeton, prefer);
        }
        return r;
    }

    private void verifier(Reponse r, String quoi) throws Echec {
        if (r.statut() >= 200 && r.statut() < 300) return;
        boolean passager = r.statut() >= 500 || r.statut() == 429 || r.statut() == 408;
        throw new Echec(quoi + " refusé par le serveur (" + r.statut() + " " + codeErreur(r.corps()) + ")", passager);
    }

    /** Code d'erreur renvoyé par Supabase (ex : invalid_credentials, 42501) : court, sans donnée personnelle. */
    private static String codeErreur(String corps) {
        Map<String, Object> o = Json.objet(corps);
        for (String cle : new String[]{"error_code", "code", "error"}) {
            if (o.get(cle) instanceof String s && s.matches("[A-Za-z0-9_.-]{1,40}")) return s;
        }
        return "";
    }

    private Reponse envoyer(String methode, String chemin, String corps, String jetonAcces, String prefer) throws Echec {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(reglages.url() + chemin))
                    .timeout(Duration.ofSeconds(15))
                    .header("apikey", reglages.cle())
                    .header("Accept", "application/json");
            if (jetonAcces != null) b.header("Authorization", "Bearer " + jetonAcces);
            if (prefer != null) b.header("Prefer", prefer);
            if (corps != null) b.header("Content-Type", "application/json");
            b.method(methode, corps == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(corps, StandardCharsets.UTF_8));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reponse(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Echec("synchronisation interrompue", true);
        } catch (IOException e) {
            // le message de l'exception n'est pas repris (il peut contenir l'adresse complète)
            throw new Echec("serveur du classement injoignable (" + e.getClass().getSimpleName() + ")", true);
        } catch (IllegalArgumentException e) {
            throw new Echec(CLE_URL + " ou " + CLE_API + " invalide dans config.properties", false);
        }
    }

    // ---------------------------------------------------------------------
    // État local : classement_sync.json (aucun secret)
    //   {"version":1,"compte":"...","appareil":"centrale-etudiant-java-xxxx","lignes":{"<id>":"<empreinte>"}}
    // ---------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void chargerEtat() {
        if (etatCharge) return;
        etatCharge = true;
        lignes.clear();
        appareil = null;
        try {
            if (Files.exists(fichierEtat)) {
                Map<String, Object> o = Json.objet(Files.readString(fichierEtat, StandardCharsets.UTF_8));
                if (o.get("appareil") instanceof String a && a.startsWith(PREFIXE_APPAREIL) && a.length() <= 100) appareil = a;
                // autre compte ou autre projet : ce qu'on sait du serveur ne vaut plus
                if (reglages.compte().equals(o.get("compte")) && o.get("lignes") instanceof Map<?, ?> m) {
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                        if (e.getValue() instanceof String s) lignes.put(e.getKey(), s);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Classement : lecture de " + fichierEtat.getFileName() + " impossible, tout sera renvoyé");
        }
        if (appareil == null) appareil = PREFIXE_APPAREIL + UUID.randomUUID().toString().substring(0, 8);
    }

    private void enregistrerEtat() {
        try {
            StringWriter w = new StringWriter();
            try (JsonGenerator g = JSON.createGenerator(w)) {
                g.writeStartObject();
                g.writeNumberField("version", 1);
                g.writeStringField("compte", reglages.compte());
                g.writeStringField("appareil", appareil);
                g.writeObjectFieldStart("lignes");
                for (Map.Entry<String, String> e : lignes.entrySet()) g.writeStringField(e.getKey(), e.getValue());
                g.writeEndObject();
                g.writeEndObject();
            }
            if (fichierEtat.getParent() != null) Files.createDirectories(fichierEtat.getParent());
            Path tmp = fichierEtat.resolveSibling(fichierEtat.getFileName() + ".tmp");
            Files.writeString(tmp, w.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, fichierEtat, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Classement : écriture de " + fichierEtat.getFileName() + " impossible");
        }
    }

    // =====================================================================
    // INSTANCE DE L'APPLI (réglages de config.properties, notes de evaluations.csv)
    // =====================================================================
    private static RankingSync appli;

    static synchronized RankingSync appli() {
        if (appli == null) {
            appli = new RankingSync(Reglages.lire(Config::get), Config.fichier("classement_sync.json"), () -> {
                // fichier des notes absent (jamais créé, ou dossier inaccessible) : on ne supprime rien du classement
                if (!Files.exists(Note.Stockage.FILE)) return null;
                return Note.Stockage.visibles();
            });
        }
        return appli;
    }

    /** À appeler après chaque ajout / modification / suppression de note, et au démarrage. Ne bloque jamais. */
    static void demander() {
        try {
            appli().planifier();
        } catch (Throwable t) {
            System.err.println("Classement : synchronisation impossible (" + t.getClass().getSimpleName() + ")");
        }
    }
}
