package com.centrale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Programme du BUT1 Informatique, semestre 1 : ressources, SAÉ, UE et matrice des coefficients.
 *
 * Repris à l'identique de github.com/TomH0999/gestionnaire_de_notes (js/core/program.js et grades-calc.js),
 * conforme au fichier Excel officiel du BUT1 :
 *  1. moyenne d'une matière = somme(note ramenée sur 20 x coef de la note) / somme(coefs des notes) ;
 *  2. moyenne d'une UE = somme(moyenne matière x coef de la matière dans l'UE) / somme(coefs),
 *     en ignorant les matières sans note (ni au numérateur ni au dénominateur) ;
 *  3. moyenne générale = moyenne SIMPLE des UE qui ont une moyenne ;
 *  4. statut : les 6 UE calculées -> refusé si plus de 2 UE sous 10 ou une UE sous 8,
 *     validé avec rattrapage si 1 ou 2 UE sous 10, validé sinon.
 *
 * L'ordre des matières dans chaque UE est celui de l'original (les sommes de nombres à virgule
 * en dépendent au dernier chiffre près).
 */
final class Programme {

    private Programme() {}

    record Matiere(String code, String nom, boolean sae) {
        /** "R1.01 · Initiation au développement" */
        String libelle() {
            return code + " · " + nom;
        }
    }

    record Ue(String code, String nom) {}

    static final List<Matiere> RESSOURCES = List.of(
            new Matiere("R1.01", "Initiation au développement", false),
            new Matiere("R1.02", "Développement d'interfaces web", false),
            new Matiere("R1.03", "Introduction à l'architecture des ordinateurs", false),
            new Matiere("R1.04", "Introduction aux systèmes d'exploitation", false),
            new Matiere("R1.05", "Introduction aux bases de données", false),
            new Matiere("R1.06", "Mathématiques discrètes", false),
            new Matiere("R1.07", "Outils mathématiques fondamentaux", false),
            new Matiere("R1.08", "Gestion de projet & Organisation", false),
            new Matiere("R1.09", "Économie durable et numérique", false),
            new Matiere("R1.10", "Anglais technique", false),
            new Matiere("R1.11", "Bases de la communication", false),
            new Matiere("R1.12", "PPP - Projet Personnel et Professionnel", false));

    static final List<Matiere> SAES = List.of(
            new Matiere("SAE 1.01", "Implémentation d'un besoin client", true),
            new Matiere("SAE 1.02", "Comparaison d'approches algorithmiques", true),
            new Matiere("SAE 1.03", "Installation d'un poste pour le dev.", true),
            new Matiere("SAE 1.04", "Création d'une base de données", true),
            new Matiere("SAE 1.05", "Recueil de besoins", true),
            new Matiere("SAE 1.06", "Découverte de l'environnement éco.", true));

    static final List<Ue> UES = List.of(
            new Ue("UE 1.1", "Réaliser un développement d'application"),
            new Ue("UE 1.2", "Optimiser des applications"),
            new Ue("UE 1.3", "Administrer des systèmes informatiques"),
            new Ue("UE 1.4", "Gérer des données de l'information"),
            new Ue("UE 1.5", "Conduire un projet"),
            new Ue("UE 1.6", "Travailler dans une équipe informatique"));

    /** Matrice des coefficients : UE -> (matière -> coefficient), dans l'ordre de l'original. */
    static final Map<String, Map<String, Integer>> COEFFICIENTS_UE = new LinkedHashMap<>();

    static {
        ue("UE 1.1", "SAE 1.01", 40, "R1.01", 42, "R1.02", 12, "R1.10", 6);
        ue("UE 1.2", "SAE 1.02", 40, "R1.01", 24, "R1.03", 3, "R1.04", 3, "R1.06", 15, "R1.07", 15);
        ue("UE 1.3", "SAE 1.03", 40, "R1.03", 21, "R1.04", 21, "R1.10", 12, "R1.11", 6);
        ue("UE 1.4", "SAE 1.04", 40, "R1.05", 36, "R1.06", 18, "R1.09", 6);
        ue("UE 1.5", "SAE 1.05", 40, "R1.02", 18, "R1.08", 27, "R1.11", 15);
        ue("UE 1.6", "SAE 1.06", 40, "R1.02", 5, "R1.08", 11, "R1.09", 11, "R1.10", 11, "R1.11", 11, "R1.12", 11);
    }

    private static void ue(String code, Object... paires) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < paires.length; i += 2) m.put((String) paires[i], (Integer) paires[i + 1]);
        COEFFICIENTS_UE.put(code, m);
    }

    /** Ressources puis SAÉ, dans l'ordre du programme. */
    static final List<Matiere> TOUTES;
    private static final Map<String, Matiere> PAR_CODE = new LinkedHashMap<>();

    static {
        List<Matiere> l = new ArrayList<>(RESSOURCES);
        l.addAll(SAES);
        TOUTES = List.copyOf(l);
        for (Matiere m : TOUTES) PAR_CODE.put(m.code(), m);
    }

    static Matiere matiere(String code) {
        return PAR_CODE.get(code);
    }

    static boolean existe(String code) {
        return PAR_CODE.containsKey(code);
    }

    static Ue ue(String code) {
        for (Ue u : UES) if (u.code().equals(code)) return u;
        return null;
    }

    /** UE dans lesquelles compte une matière, avec son coefficient (ex : R1.01 -> UE 1.1 : 42, UE 1.2 : 24). */
    static Map<String, Integer> uesDe(String codeMatiere) {
        Map<String, Integer> res = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : COEFFICIENTS_UE.entrySet()) {
            Integer c = e.getValue().get(codeMatiere);
            if (c != null) res.put(e.getKey(), c);
        }
        return res;
    }

    // =====================================================================
    // CALCUL DES MOYENNES
    // =====================================================================

    /** Une évaluation, vue par le calcul. */
    interface Evaluation {
        String code();
        double note();
        double bareme();
        double coef();
    }

    enum Statut {
        NON_CALCULE("Non calculé"),
        EN_COURS("En cours d'évaluation"),
        VALIDE("Semestre validé"),
        RATTRAPAGE("Validé avec rattrapage"),
        REFUSE("Semestre non validé");

        final String libelle;

        Statut(String libelle) {
            this.libelle = libelle;
        }
    }

    /** Détail d'une ligne du calcul d'une UE (pour l'infobulle). */
    record LigneUe(String code, Double moyenne, int coef) {}

    record Resultat(Map<String, Double> moyennesMatieres, Map<String, Double> moyennesUe,
                    Double generale, Statut statut, int nbEvaluations) {

        /** Lignes du calcul d'une UE, dans l'ordre de la matrice. */
        List<LigneUe> detailUe(String codeUe) {
            List<LigneUe> l = new ArrayList<>();
            Map<String, Integer> coefs = COEFFICIENTS_UE.get(codeUe);
            if (coefs == null) return l;
            for (Map.Entry<String, Integer> e : coefs.entrySet()) {
                l.add(new LigneUe(e.getKey(), moyennesMatieres.get(e.getKey()), e.getValue()));
            }
            return l;
        }
    }

    /**
     * Calcule toutes les moyennes. Les évaluations doivent être triées par date de création
     * (comme sortGradesForCalculation de l'original).
     */
    static Resultat calculer(List<? extends Evaluation> evaluations) {
        // 1. Moyenne de chaque matière / SAÉ
        Map<String, Double> moyennesMatieres = new LinkedHashMap<>();
        for (Matiere m : TOUTES) {
            double sommeNotes = 0, sommeCoefs = 0;
            boolean aDesNotes = false;
            for (Evaluation e : evaluations) {
                if (!e.code().equals(m.code())) continue;
                aDesNotes = true;
                double surVingt = (e.note() / e.bareme()) * 20;
                sommeNotes += surVingt * e.coef();
                sommeCoefs += e.coef();
            }
            moyennesMatieres.put(m.code(), aDesNotes && sommeCoefs > 0 ? sommeNotes / sommeCoefs : null);
        }

        // 2. Moyennes d'UE
        Map<String, Double> moyennesUe = new LinkedHashMap<>();
        for (Ue ue : UES) {
            double somme = 0, totalCoefs = 0;
            for (Map.Entry<String, Integer> e : COEFFICIENTS_UE.get(ue.code()).entrySet()) {
                Double moy = moyennesMatieres.get(e.getKey());
                if (moy != null) {
                    somme += moy * e.getValue();
                    totalCoefs += e.getValue();
                }
            }
            moyennesUe.put(ue.code(), totalCoefs > 0 ? somme / totalCoefs : null);
        }

        // 3. Moyenne générale : moyenne simple des UE calculées
        List<Double> ues = moyennesUe.values().stream().filter(v -> v != null).toList();
        Double generale = null;
        if (!ues.isEmpty()) {
            double s = 0;
            for (double v : ues) s += v;
            generale = s / ues.size();
        }

        // 4. Statut d'obtention du semestre
        Statut statut = Statut.NON_CALCULE;
        if (ues.size() == UES.size()) {
            long sousDix = ues.stream().filter(v -> v < 10).count();
            boolean sousHuit = ues.stream().anyMatch(v -> v < 8);
            if (sousDix > 2 || sousHuit) statut = Statut.REFUSE;
            else if (sousDix >= 1) statut = Statut.RATTRAPAGE;
            else statut = Statut.VALIDE;
        } else if (!ues.isEmpty()) {
            statut = Statut.EN_COURS;
        }

        return new Resultat(moyennesMatieres, moyennesUe, generale, statut, evaluations.size());
    }

    // =====================================================================
    // ANCIENNES MATIÈRES (version libre de l'appli) -> CODES DU PROGRAMME
    // =====================================================================

    /**
     * Retrouve le code d'une matière saisie librement dans l'ancienne version (« Init_Dev », « Anglais »...).
     * null si on ne sait pas la rattacher.
     */
    static String codePourAncienNom(String nom) {
        String n = sansAccents(nom).toLowerCase().replace('_', ' ').replace('-', ' ').trim();
        // déjà un code ? ("R1.01", "r101", "SAE 1.03", "sae1.03")
        String compact = n.replace(" ", "").replace(".", "");
        for (Matiere m : TOUTES) {
            if (compact.equals(m.code().toLowerCase().replace(" ", "").replace(".", ""))) return m.code();
        }
        if (n.startsWith("sae") || n.startsWith("saé")) return null;
        if (n.contains("init") && n.contains("dev")) return "R1.01";
        if (n.contains("web") || n.contains("interface") || n.contains("html")) return "R1.02";
        if (n.contains("archi")) return "R1.03";
        if (n.contains("syst") || n.contains("linux")) return "R1.04";
        if (n.contains("bd") || n.contains("base") || n.contains("sql") || n.contains("donnee")) return "R1.05";
        if (n.contains("discret")) return "R1.06";
        if (n.contains("outil")) return "R1.07";
        if (n.contains("math")) return "R1.06";
        if (n.contains("gestion") || n.contains("projet")) return "R1.08";
        if (n.contains("eco")) return "R1.09";
        if (n.contains("anglais") || n.contains("english")) return "R1.10";
        if (n.contains("commu")) return "R1.11";
        if (n.contains("ppp")) return "R1.12";
        if (n.contains("dev")) return "R1.01";
        return null;
    }

    private static String sansAccents(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
