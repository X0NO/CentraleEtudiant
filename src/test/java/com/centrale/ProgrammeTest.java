package com.centrale;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.TestCase;

/**
 * Le calcul des moyennes doit donner EXACTEMENT (au bit près) les mêmes résultats que
 * le gestionnaire de notes de TomH0999 (github.com/TomH0999/gestionnaire_de_notes).
 *
 * Jeux de données : copiés de son fichier tests/golden/datasets.js.
 * Résultats attendus : son fichier tests/golden/golden-expected.json (valeurs figées de la version d'origine),
 * copié dans src/test/resources/golden-gestionnaire-de-notes.json.
 */
public class ProgrammeTest extends TestCase {

    record G(String code, double note, double bareme, double coef) implements Programme.Evaluation {}

    // ---------------------------------------------------------------------
    // Jeux de données (identiques à datasets.js)
    // ---------------------------------------------------------------------
    static G g(String code, double note) { return new G(code, note, 20, 1); }
    static G g(String code, double note, double bareme, double coef) { return new G(code, note, bareme, coef); }

    static final String[] CODES = {
        "R1.01", "R1.02", "R1.03", "R1.04", "R1.05", "R1.06", "R1.07", "R1.08", "R1.09", "R1.10", "R1.11", "R1.12",
        "SAE 1.01", "SAE 1.02", "SAE 1.03", "SAE 1.04", "SAE 1.05", "SAE 1.06"};

    /** Port Java de mulberry32 (générateur pseudo-aléatoire de datasets.js). */
    static final class Mulberry32 {
        int a;
        Mulberry32(int seed) { a = seed; }
        double next() {
            a = a + 0x6d2b79f5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
    }

    static List<G> aleatoire(int seed, int count, boolean coefZero) {
        Mulberry32 rnd = new Mulberry32(seed);
        double[] maxes = {20, 20, 20, 10, 40, 100, 5};
        double[] coefs = coefZero ? new double[]{0, 0.5, 1, 1, 1.5, 2, 3} : new double[]{0.5, 1, 1, 1.5, 2, 3};
        List<G> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = CODES[(int) Math.floor(rnd.next() * CODES.length)];
            double max = maxes[(int) Math.floor(rnd.next() * maxes.length)];
            double score = rnd.next() * max;
            score = Math.round(score * 100) / 100.0;
            double coef = coefs[(int) Math.floor(rnd.next() * coefs.length)];
            out.add(new G(code, score, max, coef));
        }
        return out;
    }

    static Map<String, List<G>> datasets() {
        Map<String, List<G>> d = new LinkedHashMap<>();
        d.put("exemple-initial", List.of(g("R1.01", 15, 20, 1), g("R1.02", 14, 20, 2), g("SAE 1.01", 16, 20, 1)));
        d.put("aucune-note", List.of());
        d.put("cas-normal", List.of(
                g("R1.01", 15, 20, 1), g("R1.01", 12.5, 20, 2), g("R1.01", 8, 10, 1),
                g("R1.02", 14, 20, 1), g("R1.02", 31, 40, 1.5),
                g("R1.03", 11, 20, 1), g("R1.04", 9.5, 20, 1), g("R1.04", 13, 20, 1),
                g("R1.05", 16, 20, 2), g("R1.06", 7, 20, 1), g("R1.06", 12, 20, 3),
                g("R1.07", 10, 20, 1), g("R1.08", 15, 20, 1), g("R1.09", 13.5, 20, 1),
                g("R1.10", 17, 20, 1), g("R1.11", 12, 20, 1), g("R1.12", 14, 20, 1),
                g("SAE 1.01", 16, 20, 1), g("SAE 1.02", 11, 20, 1), g("SAE 1.03", 13, 20, 1),
                g("SAE 1.04", 15, 20, 1), g("SAE 1.05", 12, 20, 1), g("SAE 1.06", 14, 20, 1)));
        d.put("notes-manquantes", List.of(
                g("R1.01", 13, 20, 1), g("SAE 1.01", 9, 20, 1), g("R1.06", 11, 20, 1), g("R1.07", 6.5, 20, 1),
                g("R1.04", 15, 20, 1), g("R1.05", 12, 20, 1), g("SAE 1.05", 10, 20, 1)));
        d.put("coefficient-zero", List.of(
                g("R1.01", 18, 20, 0), g("R1.01", 4, 20, 0), g("R1.02", 20, 20, 0), g("R1.02", 10, 20, 1),
                g("SAE 1.01", 12, 20, 1), g("R1.10", 15, 20, 2)));
        d.put("notes-decimales", List.of(
                g("R1.01", 0.1, 20, 1), g("R1.01", 0.2, 20, 1), g("R1.02", 10.005, 20, 1),
                g("R1.03", 13.333, 20, 1), g("R1.03", 13.334, 20, 2), g("R1.04", 2, 3, 1),
                g("R1.05", 12.75, 20, 0.5), g("R1.05", 14.25, 20, 1.5), g("R1.06", 9.995, 20, 1),
                g("SAE 1.01", 14.125, 20, 1), g("SAE 1.02", 7.777, 10, 1),
                g("SAE 1.03", 0.3, 0.6, 1), g("SAE 1.04", 16.666, 20, 1)));
        List<G> zero = new ArrayList<>(), vingt = new ArrayList<>();
        for (String c : CODES) { zero.add(g(c, 0)); vingt.add(g(c, 20)); }
        d.put("zero-sur-vingt", zero);
        d.put("vingt-sur-vingt", vingt);
        d.put("matiere-sans-note", List.of(g("R1.10", 12, 20, 1)));
        d.put("rattrapage-bornes", List.of(
                g("SAE 1.01", 10), g("R1.01", 10), g("R1.02", 10), g("R1.10", 10),
                g("SAE 1.02", 8), g("R1.03", 8), g("R1.04", 8), g("R1.06", 8), g("R1.07", 8),
                g("SAE 1.03", 12), g("R1.11", 11), g("SAE 1.04", 14), g("R1.05", 13), g("R1.09", 12),
                g("SAE 1.05", 15), g("R1.08", 12), g("SAE 1.06", 13), g("R1.12", 16)));
        d.put("borne-huit", List.of(
                g("SAE 1.01", 8), g("R1.01", 8), g("R1.02", 8), g("R1.10", 8),
                g("SAE 1.02", 20), g("R1.03", 20), g("R1.04", 20), g("R1.06", 20), g("R1.07", 20),
                g("SAE 1.03", 20), g("R1.11", 20), g("SAE 1.04", 15), g("R1.05", 15), g("R1.09", 15),
                g("SAE 1.05", 20), g("R1.08", 20), g("SAE 1.06", 20), g("R1.12", 20)));
        d.put("sous-huit", List.of(
                g("SAE 1.01", 7.9), g("R1.01", 8), g("R1.02", 8), g("R1.10", 8),
                g("SAE 1.02", 20), g("R1.03", 20), g("R1.04", 20), g("R1.06", 20), g("R1.07", 20),
                g("SAE 1.03", 20), g("R1.11", 20), g("SAE 1.04", 15), g("R1.05", 15), g("R1.09", 15),
                g("SAE 1.05", 20), g("R1.08", 20), g("SAE 1.06", 20), g("R1.12", 20)));
        d.put("trois-ue-sous-dix", List.of(
                g("SAE 1.01", 9), g("R1.01", 9), g("R1.02", 9), g("R1.10", 9),
                g("SAE 1.02", 9), g("R1.03", 9), g("R1.04", 9), g("R1.06", 9), g("R1.07", 9),
                g("SAE 1.03", 9), g("R1.11", 9), g("SAE 1.04", 15), g("R1.05", 15), g("R1.09", 15),
                g("SAE 1.05", 15), g("R1.08", 15), g("SAE 1.06", 15), g("R1.12", 15)));
        d.put("baremes-varies", List.of(
                g("R1.01", 7, 10, 1), g("R1.01", 33, 40, 2), g("R1.01", 85, 100, 1),
                g("R1.02", 4.5, 5, 1), g("R1.02", 22, 20, 1), g("SAE 1.01", 1, 1, 1), g("SAE 1.01", 0.5, 2, 3)));
        d.put("code-inconnu", List.of(g("R9.99", 20, 20, 1), g("R1.01", 10, 20, 1), g("SAE 9.99", 0, 20, 5)));
        d.put("volumineux-600", aleatoire(42, 600, false));
        d.put("aleatoire-coeff-zero", aleatoire(7, 120, true));
        return d;
    }

    // ---------------------------------------------------------------------
    // Lecture minimale du JSON attendu (pas de bibliothèque JSON dans le projet)
    // ---------------------------------------------------------------------
    static String json;

    static String golden() throws Exception {
        if (json == null) {
            try (InputStream in = ProgrammeTest.class.getResourceAsStream("/golden-gestionnaire-de-notes.json")) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return json;
    }

    /** Bloc "calc" d'un jeu de données. */
    static String calc(String nom) throws Exception {
        String j = golden();
        int i = j.indexOf("\"" + nom + "\": {");
        assertTrue("jeu absent du JSON : " + nom, i >= 0);
        int c = j.indexOf("\"calc\"", i);
        int fin = j.indexOf("\"display\"", c);
        return j.substring(c, fin);
    }

    static Map<String, Double> objet(String calc, String cle) {
        int i = calc.indexOf("\"" + cle + "\"");
        String bloc = calc.substring(i, calc.indexOf('}', i));
        Map<String, Double> m = new LinkedHashMap<>();
        Matcher x = Pattern.compile("\"([^\"]+)\": (null|[-0-9.eE+]+)").matcher(bloc);
        while (x.find()) m.put(x.group(1), x.group(2).equals("null") ? null : Double.parseDouble(x.group(2)));
        return m;
    }

    static Double nombre(String calc, String cle) {
        Matcher x = Pattern.compile("\"" + cle + "\": (null|[-0-9.eE+]+)").matcher(calc);
        assertTrue(x.find());
        return x.group(1).equals("null") ? null : Double.parseDouble(x.group(1));
    }

    static String statut(String calc) {
        Matcher x = Pattern.compile("\"status\": \"([^\"]+)\"").matcher(calc);
        assertTrue(x.find());
        return x.group(1);
    }

    static Programme.Statut statutJava(String s) {
        return switch (s) {
            case "OUI (Validé)" -> Programme.Statut.VALIDE;
            case "OUI avec rattrapage" -> Programme.Statut.RATTRAPAGE;
            case "NON (Refusé)" -> Programme.Statut.REFUSE;
            case "En cours d'évaluation" -> Programme.Statut.EN_COURS;
            default -> Programme.Statut.NON_CALCULE;
        };
    }

    static void egal(String message, Double attendu, Double obtenu) {
        if (attendu == null) {
            assertNull(message, obtenu);
        } else {
            assertNotNull(message + " (attendu " + attendu + ")", obtenu);
            // égalité exacte, bit à bit : même ordre d'opérations que l'original
            assertEquals(message, Double.doubleToLongBits(attendu), Double.doubleToLongBits(obtenu));
        }
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------
    public void testIdentiqueAuGestionnaireDeNotes() throws Exception {
        for (Map.Entry<String, List<G>> d : datasets().entrySet()) {
            String nom = d.getKey();
            String calc = calc(nom);
            Programme.Resultat r = Programme.calculer(d.getValue());

            for (Map.Entry<String, Double> e : objet(calc, "subjectAverages").entrySet()) {
                egal(nom + " / " + e.getKey(), e.getValue(), r.moyennesMatieres().get(e.getKey()));
            }
            for (Map.Entry<String, Double> e : objet(calc, "ueAverages").entrySet()) {
                egal(nom + " / " + e.getKey(), e.getValue(), r.moyennesUe().get(e.getKey()));
            }
            egal(nom + " / générale", nombre(calc, "globalAverage"), r.generale());
            assertEquals(nom + " / statut", statutJava(statut(calc)), r.statut());
            assertEquals(nom + " / nb", nombre(calc, "totalEvals").intValue(), r.nbEvaluations());
        }
    }

    public void testAnciensNomsDeMatieres() {
        assertEquals("R1.01", Programme.codePourAncienNom("Init_Dev"));
        assertEquals("R1.02", Programme.codePourAncienNom("Dev_Web"));
        assertEquals("R1.04", Programme.codePourAncienNom("Intro_Syst"));
        assertEquals("R1.05", Programme.codePourAncienNom("Intro_BD"));
        assertEquals("R1.06", Programme.codePourAncienNom("Maths"));
        assertEquals("R1.08", Programme.codePourAncienNom("Gestion"));
        assertEquals("R1.10", Programme.codePourAncienNom("Anglais"));
        assertEquals("R1.11", Programme.codePourAncienNom("Commu"));
        assertEquals("R1.12", Programme.codePourAncienNom("PPP"));
        assertEquals("SAE 1.03", Programme.codePourAncienNom("sae1.03"));
        assertEquals("R1.07", Programme.codePourAncienNom("r107"));
        assertNull(Programme.codePourAncienNom("Sport"));
    }
}
