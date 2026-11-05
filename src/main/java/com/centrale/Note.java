package com.centrale;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * Notes et moyennes du BUT1 Informatique (semestre 1).
 *
 * - Calcul identique au gestionnaire de notes de TomH0999 (voir {@link Programme}) :
 *   matières R1.01 à R1.12 et SAÉ 1.01 à 1.06, moyennes d'UE coefficientées, moyenne générale
 *   = moyenne des 6 UE, statut d'obtention du semestre.
 * - Chaque évaluation : nom (DS1, TP noté...), note, barème (sur 20, sur 30...), coefficient.
 * - Enregistrement instantané dans evaluations.csv ; la BDD (facultative) est synchronisée
 *   en arrière-plan, sans jamais faire attendre l'interface (voir {@link Bdd}).
 */
public class Note {

    // =====================================================================
    // MODÈLE
    // =====================================================================
    static final class Evaluation implements Programme.Evaluation {
        final String uid;
        String code;
        String nom;
        double note;
        double bareme;
        double coef;
        final long creeLe;
        String statut;

        Evaluation(String uid, String code, String nom, double note, double bareme, double coef, long creeLe, String statut) {
            this.uid = uid;
            this.code = code;
            this.nom = nom;
            this.note = note;
            this.bareme = bareme;
            this.coef = coef;
            this.creeLe = creeLe;
            this.statut = statut;
        }

        @Override public String code() { return code; }
        @Override public double note() { return note; }
        @Override public double bareme() { return bareme; }
        @Override public double coef() { return coef; }

        double surVingt() {
            return note / bareme * 20;
        }

        Evaluation copie() {
            return new Evaluation(uid, code, nom, note, bareme, coef, creeLe, statut);
        }

        String toLine() {
            return uid + ";" + code + ";" + Double.toString(note) + ";" + Double.toString(bareme) + ";"
                    + Double.toString(coef) + ";" + creeLe + ";" + statut + ";" + Stockage.coder(nom);
        }
    }

    /** Ordre du calcul (comme l'original) : date de création, puis identifiant. */
    static final Comparator<Evaluation> ORDRE =
            Comparator.comparingLong((Evaluation e) -> e.creeLe).thenComparing(e -> e.uid);

    // =====================================================================
    // STOCKAGE LOCAL : evaluations.csv
    //
    //     uid;code;note;bareme;coef;cree_le;statut;nom
    //   statut : SYNC = identique dans la BDD
    //            ADD  = créée ou modifiée ici, pas encore envoyée à la BDD
    //            MOD  = modifiée ici, pas encore envoyée à la BDD
    //            DEL  = supprimée ici, pas encore supprimée de la BDD
    //   nom en dernier (peut contenir des ';').
    //
    // L'interface ne lit et n'écrit QUE ce fichier (instantané). La BDD est mise à jour ensuite,
    // en arrière-plan, par Database.synchroniser().
    // =====================================================================
    static final class Stockage {
        static final Path FILE = Config.fichier("evaluations.csv");
        /** Fichier de l'ancienne version (matières libres) : converti une fois, puis laissé tel quel. */
        static final Path ANCIEN = Config.fichier("notes_backup.csv");
        static final String HEADER = "uid;code;note;bareme;coef;cree_le;statut;nom";
        static final String SYNC = "SYNC";
        static final String ADD = "ADD";
        static final String MOD = "MOD";
        static final String DEL = "DEL";

        /** Matières de l'ancienne version qui n'ont pas pu être rattachées au programme (affiché une fois). */
        static volatile List<String> nonMigrees = List.of();

        static synchronized List<Evaluation> lire() {
            if (!Files.exists(FILE)) migrerAncienneVersion();
            List<Evaluation> res = new ArrayList<>();
            if (!Files.exists(FILE)) return res;
            try {
                for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                    if (line.equals(HEADER) || line.isBlank()) continue;
                    String[] p = line.split(";", 8);
                    if (p.length != 8) continue;
                    try {
                        res.add(new Evaluation(p[0], p[1], decoder(p[7]), Double.parseDouble(p[2]),
                                Double.parseDouble(p[3]), Double.parseDouble(p[4]), Long.parseLong(p[5]), p[6]));
                    } catch (NumberFormatException ignored) {
                        // ligne illisible : ignorée
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Lecture de " + FILE + " impossible", e);
            }
            return res;
        }

        /** Écriture atomique (fichier temporaire puis remplacement). */
        static synchronized void ecrire(List<Evaluation> l) {
            List<String> lignes = new ArrayList<>();
            lignes.add(HEADER);
            for (Evaluation e : l) lignes.add(e.toLine());
            try {
                Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
                Files.write(tmp, lignes, StandardCharsets.UTF_8);
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("Erreur d'écriture de " + FILE + " : " + e.getMessage());
            }
        }

        static String coder(String s) {
            return s.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n");
        }

        static String decoder(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char suivant = s.charAt(++i);
                    sb.append(suivant == 'n' ? '\n' : suivant);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        // ---------- lecture pour l'affichage ----------

        /** Évaluations visibles (pas supprimées), dans l'ordre du calcul. */
        static synchronized List<Evaluation> visibles() {
            List<Evaluation> res = new ArrayList<>();
            for (Evaluation e : lire()) if (!e.statut.equals(DEL)) res.add(e);
            res.sort(ORDRE);
            return res;
        }

        static synchronized int enAttente() {
            int n = 0;
            for (Evaluation e : lire()) if (!e.statut.equals(SYNC)) n++;
            return n;
        }

        // ---------- modifications (instantanées) ----------

        static synchronized void ajouter(Evaluation e) {
            List<Evaluation> l = lire();
            e.statut = ADD;
            l.add(e);
            ecrire(l);
        }

        static synchronized void modifier(Evaluation nouvelle) {
            List<Evaluation> l = lire();
            for (Evaluation e : l) {
                if (e.uid.equals(nouvelle.uid) && !e.statut.equals(DEL)) {
                    e.code = nouvelle.code;
                    e.nom = nouvelle.nom;
                    e.note = nouvelle.note;
                    e.bareme = nouvelle.bareme;
                    e.coef = nouvelle.coef;
                    if (!e.statut.equals(ADD)) e.statut = MOD;
                    ecrire(l);
                    return;
                }
            }
        }

        /** @return une copie de l'évaluation telle qu'elle était (pour « Annuler »), null si absente. */
        static synchronized Evaluation supprimer(String uid) {
            List<Evaluation> l = lire();
            for (int i = 0; i < l.size(); i++) {
                Evaluation e = l.get(i);
                if (e.uid.equals(uid) && !e.statut.equals(DEL)) {
                    Evaluation avant = e.copie();
                    if (e.statut.equals(ADD)) l.remove(i); // jamais envoyée : on l'oublie simplement
                    else e.statut = DEL;
                    ecrire(l);
                    return avant;
                }
            }
            return null;
        }

        /** Annule une suppression. */
        static synchronized void restaurer(Evaluation avant) {
            List<Evaluation> l = lire();
            for (Evaluation e : l) {
                if (e.uid.equals(avant.uid)) {
                    e.statut = avant.statut.equals(ADD) ? ADD : MOD; // MOD = renvoyée à la BDD (sans risque)
                    ecrire(l);
                    return;
                }
            }
            Evaluation r = avant.copie();
            r.statut = ADD;
            l.add(r);
            ecrire(l);
        }

        // ---------- synchronisation ----------

        static synchronized List<Evaluation> copiesEnAttente() {
            List<Evaluation> res = new ArrayList<>();
            for (Evaluation e : lire()) if (!e.statut.equals(SYNC)) res.add(e.copie());
            return res;
        }

        /**
         * Après un aller-retour avec la BDD : le fichier devient le contenu de la BDD, plus les
         * changements locaux qui n'y sont pas encore (échec d'envoi, ou faits pendant la synchro).
         * @param envoyees uid -> ligne envoyée avec succès
         * @return true s'il reste des changements locaux à envoyer
         */
        static synchronized boolean fusionner(List<Evaluation> distantes, Map<String, String> envoyees) {
            Map<String, Evaluation> res = new LinkedHashMap<>();
            for (Evaluation d : distantes) res.put(d.uid, d);
            boolean reste = false;
            for (Evaluation l : lire()) {
                String envoyee = envoyees.get(l.uid);
                boolean changeePendant = envoyee != null && !envoyee.equals(l.toLine());
                if (l.statut.equals(SYNC) && !changeePendant) continue; // la BDD fait foi
                if (envoyee != null && !changeePendant) continue;       // envoyée telle quelle : déjà dans la BDD
                if (l.statut.equals(SYNC)) l.statut = MOD;               // ex : suppression annulée pendant l'envoi
                res.put(l.uid, l);
                reste = true;
            }
            List<Evaluation> l = new ArrayList<>(res.values());
            l.sort(ORDRE);
            ecrire(l);
            return reste;
        }

        // ---------- conversion de l'ancienne version (matières libres) ----------

        /**
         * notes_backup.csv (type;matiere;valeur;quotient;coefficient;statut) -> evaluations.csv.
         * Les matières sont rattachées aux codes du programme (« Init_Dev » -> R1.01...).
         * Identifiants calculés à partir du contenu : deux ordinateurs qui convertissent
         * le même fichier obtiennent les mêmes, donc pas de doublon dans la BDD.
         */
        static void migrerAncienneVersion() {
            if (!Files.exists(ANCIEN)) return;
            List<String> lignes;
            try {
                lignes = Files.readAllLines(ANCIEN, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return;
            }
            List<Evaluation> res = new ArrayList<>();
            Set<String> inconnues = new LinkedHashSet<>();
            Map<String, Integer> numero = new HashMap<>();
            long base = 1_788_220_800_000L; // 1er septembre 2026 : garde l'ordre de saisie
            int i = 0;
            for (String ligne : lignes) {
                String[] p = ligne.split(";");
                if (p.length < 5 || !p[0].equals("N")) continue;
                String statut = p[p.length - 1];
                if (statut.equals("DEL")) continue;
                try {
                    double valeur = Double.parseDouble(p[2]);
                    double quotient = Double.parseDouble(p[3]);
                    double coef = (p.length == 6) ? Double.parseDouble(p[4]) : 1.0;
                    if (quotient <= 0) continue;
                    String code = Programme.codePourAncienNom(p[1]);
                    if (code == null) { inconnues.add(p[1]); continue; }
                    int n = numero.merge(code, 1, Integer::sum);
                    String uid = UUID.nameUUIDFromBytes(("ancienne-note:" + i + ":" + ligne).getBytes(StandardCharsets.UTF_8)).toString();
                    res.add(new Evaluation(uid, code, "Note " + n, valeur, quotient, coef, base + i * 1000L, ADD));
                    i++;
                } catch (NumberFormatException ignored) {
                    // ligne illisible
                }
            }
            nonMigrees = List.copyOf(inconnues);
            ecrire(res);
            System.out.println(res.size() + " note(s) de l'ancienne version converties vers " + FILE
                    + (inconnues.isEmpty() ? "" : " ; non rattachées : " + inconnues));
        }
    }

    // =====================================================================
    // BASE DE DONNÉES (facultative) : table « evaluations »
    // Une seule connexion par synchronisation, toujours hors de l'interface.
    // =====================================================================
    static final class Database {

        static volatile boolean schemaPret = false;

        static void creerTables(Connection c) throws SQLException {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS evaluations ("
                        + "uid VARCHAR(36) PRIMARY KEY,"
                        + "code VARCHAR(20) NOT NULL,"
                        + "nom VARCHAR(200) NOT NULL,"
                        + "note DOUBLE PRECISION NOT NULL,"
                        + "bareme DOUBLE PRECISION NOT NULL,"
                        + "coef DOUBLE PRECISION NOT NULL,"
                        + "cree_le BIGINT NOT NULL)");
            }
            schemaPret = true;
        }

        static void envoyer(Connection c, Evaluation e) throws SQLException {
            if (e.statut.equals(Stockage.DEL)) {
                try (PreparedStatement st = c.prepareStatement("DELETE FROM evaluations WHERE uid = ?")) {
                    st.setString(1, e.uid);
                    st.executeUpdate();
                }
                return;
            }
            String sql = "INSERT INTO evaluations (uid, code, nom, note, bareme, coef, cree_le) VALUES (?, ?, ?, ?, ?, ?, ?) "
                       + "ON CONFLICT (uid) DO UPDATE SET code = EXCLUDED.code, nom = EXCLUDED.nom, note = EXCLUDED.note, "
                       + "bareme = EXCLUDED.bareme, coef = EXCLUDED.coef";
            try (PreparedStatement st = c.prepareStatement(sql)) {
                st.setString(1, e.uid);
                st.setString(2, e.code);
                st.setString(3, e.nom);
                st.setDouble(4, e.note);
                st.setDouble(5, e.bareme);
                st.setDouble(6, e.coef);
                st.setLong(7, e.creeLe);
                st.executeUpdate();
            }
        }

        static List<Evaluation> lireTout(Connection c) throws SQLException {
            List<Evaluation> l = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT uid, code, nom, note, bareme, coef, cree_le FROM evaluations ORDER BY cree_le, uid");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    l.add(new Evaluation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                            rs.getDouble(5), rs.getDouble(6), rs.getLong(7), Stockage.SYNC));
                }
            }
            return l;
        }

        /**
         * Envoie les changements locaux, puis aligne le fichier sur la BDD.
         * Sans effet (et immédiat) si la BDD n'est pas configurée ou vient d'échouer.
         * À appeler hors de l'interface : via Bdd.planifier(...).
         */
        static synchronized void synchroniser() {
            for (int passe = 0; passe < 3 && Bdd.disponible(); passe++) {
                try (Connection c = Bdd.connexion()) {
                    if (!schemaPret) creerTables(c);
                    Map<String, String> envoyees = new HashMap<>();
                    for (Evaluation e : Stockage.copiesEnAttente()) {
                        try {
                            envoyer(c, e);
                            envoyees.put(e.uid, e.toLine());
                        } catch (SQLException ex) {
                            System.err.println("Envoi de l'évaluation " + e.uid + " impossible : " + ex.getMessage());
                        }
                    }
                    boolean reste = Stockage.fusionner(lireTout(c), envoyees);
                    if (!reste) return;
                } catch (SQLException e) {
                    System.err.println("BDD injoignable, notes gardées sur cet ordinateur : " + e.getMessage());
                    return;
                }
            }
        }
    }

    /** Moyennes calculées à partir du fichier local (instantané). */
    static Programme.Resultat resultat() {
        return Programme.calculer(Stockage.visibles());
    }

    // =====================================================================
    // FORMATAGE
    // =====================================================================
    private static final DecimalFormat NOMBRE = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.FRANCE));

    /** 14.5 -> "14,5", 20.0 -> "20". */
    static String nombre(double v) {
        return NOMBRE.format(v);
    }

    /** Moyenne sur 20 : "12,34" ou "—". */
    static String moyenne(Double v) {
        return v == null ? "—" : String.format(Locale.FRANCE, "%.2f", v);
    }

    /** « 12,5 » ou « 12.5 » -> 12.5 ; null si invalide. */
    static Double lireNombre(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        if (!t.matches("\\d+(\\.\\d*)?|\\.\\d+")) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Color couleurStatut(Programme.Statut s) {
        return switch (s) {
            case VALIDE -> Theme.SUCCES;
            case RATTRAPAGE -> Theme.ATTENTION;
            case REFUSE -> Theme.RETARD;
            case EN_COURS -> Theme.ACCENT;
            default -> Theme.TEXT_DISCRET;
        };
    }

    // =====================================================================
    // INTERFACE
    // =====================================================================

    // Fenêtre ouverte (utilisée par le tableau de bord Main.java pour ne pas l'ouvrir deux fois).
    // Lancé seul, fermer la fenêtre quitte l'appli ; ouvert depuis Main, seule la fenêtre se ferme.
    static volatile JFrame fenetre;
    static int fermeture = JFrame.EXIT_ON_CLOSE;

    private static final Preferences PREFS = Preferences.userNodeForPackage(Note.class);

    // éléments mis à jour par rafraichir()
    static JLabel valeurGenerale, detailGenerale;
    static Theme.Pastille pastilleStatut, pastilleSynchro;
    static Theme.Barre barreGenerale;
    static JPanel grilleUe, listeRessources, listeSaes;
    static Bandeau bandeau;
    static final Set<String> ouvertes = new HashSet<>();

    /** Panneau qui suit la largeur de la fenêtre (le contenu se resserre au lieu de déborder). */
    static final class Colonne extends JPanel implements Scrollable {
        Colonne() {
            super(new GridBagLayout());
            setBackground(Theme.BACKGROUND);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Message en bas de la fenêtre (« Note ajoutée », « Note supprimée · Annuler »), disparaît seul. */
    static final class Bandeau extends JPanel {
        private final JLabel texte = new JLabel();
        private final JButton action = Theme.boutonSecondaire("");
        private final Timer timer = new Timer(6000, e -> setVisible(false));
        private Runnable surAction;

        Bandeau() {
            super(new FlowLayout(FlowLayout.CENTER, 12, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(0, 0, 14, 0));
            texte.setFont(Theme.FONT_TABLE.deriveFont(Font.BOLD));
            Theme.PanneauArrondi bulle = new Theme.PanneauArrondi(new FlowLayout(FlowLayout.CENTER, 12, 0), 999);
            bulle.setBackground(Theme.SURFACE_ALT);
            bulle.setBorder(new EmptyBorder(6, 16, 6, 6));
            bulle.add(texte);
            bulle.add(action);
            add(bulle);
            action.addActionListener(e -> {
                setVisible(false);
                if (surAction != null) surAction.run();
            });
            timer.setRepeats(false);
            setVisible(false);
        }

        void montrer(String message, Color couleur, String libelleAction, Runnable r) {
            texte.setText(message);
            texte.setForeground(couleur);
            surAction = r;
            action.setVisible(libelleAction != null);
            action.setText(libelleAction == null ? "" : libelleAction);
            setVisible(true);
            revalidate();
            timer.restart();
        }
    }

    // ---------------------------------------------------------------------
    // Formulaire d'évaluation (ajout en haut de page, modification en fenêtre)
    // ---------------------------------------------------------------------
    static final class Formulaire {
        final JComboBox<Programme.Matiere> matiere = new JComboBox<>(Programme.TOUTES.toArray(new Programme.Matiere[0]));
        final JTextField nom = new JTextField(14);
        final JTextField note = new JTextField(5);
        final JTextField bareme = new JTextField("20", 4);
        final JTextField coef = new JTextField("1", 4);
        final JLabel erreur = new JLabel(" ");

        Formulaire() {
            matiere.setFont(Theme.FONT_LABEL);
            matiere.setMaximumRowCount(18);
            matiere.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, sel, focus);
                    if (value instanceof Programme.Matiere m) {
                        setText(index < 0 ? m.libelle()
                                : "<html><b>" + m.code() + "</b>&nbsp;&nbsp;" + Main.echapper(m.nom()) + "</html>");
                        setToolTipText(m.nom());
                    }
                    setBorder(new EmptyBorder(5, 8, 5, 8));
                    return this;
                }
            });
            for (JTextField f : new JTextField[]{nom, note, bareme, coef}) Theme.styleChamp(f);
            nom.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "DS1, TP noté...");
            note.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "14,5");
            note.setToolTipText("Note obtenue. Tu peux aussi taper « 15/20 ».");
            erreur.setFont(Theme.FONT_PETIT);
            erreur.setForeground(Theme.RETARD);
        }

        void remplir(Evaluation e) {
            matiere.setSelectedItem(Programme.matiere(e.code));
            nom.setText(e.nom);
            note.setText(nombre(e.note));
            bareme.setText(nombre(e.bareme));
            coef.setText(nombre(e.coef));
        }

        /** Lit et vérifie la saisie ; affiche l'erreur sous le formulaire et renvoie null si invalide. */
        Evaluation lire(String uid, long creeLe) {
            Programme.Matiere m = (Programme.Matiere) matiere.getSelectedItem();
            String saisieNote = note.getText().trim();
            String saisieBareme = bareme.getText().trim();
            if (saisieNote.contains("/")) { // « 15/20 » tapé dans le champ note
                String[] p = saisieNote.split("/", 2);
                saisieNote = p[0];
                saisieBareme = p[1];
                bareme.setText(saisieBareme.trim());
                note.setText(saisieNote.trim());
            }
            Double n = lireNombre(saisieNote), b = lireNombre(saisieBareme), c = lireNombre(coef.getText());
            String nomEval = nom.getText().trim().replaceAll("\\s+", " ");

            String probleme = null;
            if (m == null) probleme = "Choisis une matière ou une SAÉ.";
            else if (saisieNote.isEmpty()) probleme = "Indique la note.";
            else if (n == null) probleme = "Note invalide (ex : 12,5).";
            else if (b == null || b <= 0) probleme = "Barème invalide (ex : 20).";
            else if (b > 1000) probleme = "Barème trop grand (1000 maximum).";
            else if (n > b) probleme = "La note dépasse le barème (" + nombre(b) + ").";
            else if (c == null || c <= 0) probleme = "Coefficient invalide (ex : 1 ou 1,5).";
            else if (c > 100) probleme = "Coefficient trop grand (100 maximum).";
            else if (nomEval.length() > 120) probleme = "Nom trop long (120 caractères maximum).";

            if (probleme != null) {
                erreur.setText(probleme);
                return null;
            }
            erreur.setText(" ");
            if (nomEval.isEmpty()) nomEval = "Évaluation";
            return new Evaluation(uid, m.code(), nomEval, n, b, c, creeLe, Stockage.ADD);
        }

        /** Champs avec leur libellé au-dessus, sur une ligne. */
        JPanel ligne(JButton bouton) {
            JPanel p = new JPanel(new GridBagLayout());
            p.setOpaque(false);
            GridBagConstraints g = new GridBagConstraints();
            g.gridy = 0;
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(0, 0, 6, 10);
            String[] libelles = {"Matière / SAÉ", "Nom de l'évaluation", "Note", "Sur", "Coef."};
            JComponent[] champs = {matiere, nom, note, bareme, coef};
            double[] poids = {0.5, 0.5, 0, 0, 0};
            for (int i = 0; i < champs.length; i++) {
                g.gridx = i;
                g.gridy = 0;
                g.weightx = poids[i];
                g.fill = GridBagConstraints.HORIZONTAL;
                p.add(Theme.surtitre(libelles[i]), g);
                g.gridy = 1;
                g.insets = new Insets(0, 0, 0, 10);
                Dimension d = champs[i].getPreferredSize();
                if (poids[i] == 0) champs[i].setPreferredSize(new Dimension(i == 2 ? 80 : 64, d.height));
                else champs[i].setPreferredSize(new Dimension(160, d.height));
                p.add(champs[i], g);
                g.insets = new Insets(0, 0, 6, 10);
            }
            if (bouton != null) {
                g.gridx = champs.length;
                g.gridy = 1;
                g.weightx = 0;
                g.insets = new Insets(0, 4, 0, 0);
                p.add(bouton, g);
            }
            g.gridx = 0;
            g.gridy = 2;
            g.gridwidth = champs.length + 1;
            g.insets = new Insets(6, 2, 0, 0);
            p.add(erreur, g);
            return p;
        }
    }

    static JPanel carteAjout() {
        Formulaire f = new Formulaire();
        String derniere = PREFS.get("derniereMatiere", null);
        if (derniere != null && Programme.existe(derniere)) f.matiere.setSelectedItem(Programme.matiere(derniere));

        JButton ajouter = Theme.bouton("Ajouter");
        ajouter.addActionListener(e -> {
            Evaluation ev = f.lire(UUID.randomUUID().toString(), System.currentTimeMillis());
            if (ev == null) return;
            Stockage.ajouter(ev);
            try { PREFS.put("derniereMatiere", ev.code); } catch (Exception ignored) { }
            ouvertes.add(ev.code);
            f.nom.setText("");
            f.note.setText("");
            f.note.requestFocusInWindow();
            apresChangement();
            bandeau.montrer("✓  " + nombre(ev.note) + "/" + nombre(ev.bareme) + " ajouté en " + ev.code,
                    Theme.SUCCES, null, null);
        });

        Theme.PanneauArrondi carte = Theme.carte(new BorderLayout(0, 12), 20);
        carte.setBorder(new EmptyBorder(18, 20, 8, 20));
        JLabel titre = new JLabel("Ajouter une évaluation");
        titre.setFont(Theme.FONT_SECTION);
        titre.setForeground(Theme.TEXT_PRIMARY);
        carte.add(titre, BorderLayout.NORTH);
        carte.add(f.ligne(ajouter), BorderLayout.CENTER);

        // Entrée dans n'importe quel champ = Ajouter
        for (JTextField c : new JTextField[]{f.nom, f.note, f.bareme, f.coef}) c.addActionListener(e -> ajouter.doClick());
        return carte;
    }

    static void modifier(Evaluation e) {
        JDialog d = new JDialog(fenetre, "Modifier l'évaluation", true);
        d.getContentPane().setBackground(Theme.BACKGROUND);
        Formulaire f = new Formulaire();
        f.remplir(e);

        JButton enregistrer = Theme.bouton("Enregistrer");
        JButton annuler = Theme.boutonSecondaire("Annuler");
        annuler.addActionListener(x -> d.dispose());
        enregistrer.addActionListener(x -> {
            Evaluation nouvelle = f.lire(e.uid, e.creeLe);
            if (nouvelle == null) return;
            Stockage.modifier(nouvelle);
            ouvertes.add(nouvelle.code);
            d.dispose();
            apresChangement();
            bandeau.montrer("✓  Évaluation modifiée", Theme.SUCCES, null, null);
        });

        JPanel boutons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        boutons.setOpaque(false);
        boutons.add(annuler);
        boutons.add(enregistrer);

        Theme.PanneauArrondi carte = Theme.carte(new BorderLayout(0, 18), 22);
        carte.add(f.ligne(null), BorderLayout.CENTER);
        carte.add(boutons, BorderLayout.SOUTH);

        JPanel fond = new JPanel(new BorderLayout());
        fond.setBackground(Theme.BACKGROUND);
        fond.setBorder(new EmptyBorder(16, 16, 16, 16));
        fond.add(carte);
        d.setContentPane(fond);
        d.getRootPane().setDefaultButton(enregistrer);
        d.pack();
        d.setSize(Math.max(760, d.getWidth()), d.getHeight());
        d.setLocationRelativeTo(fenetre);
        d.setVisible(true);
    }

    static void supprimer(Evaluation e) {
        Evaluation avant = Stockage.supprimer(e.uid);
        if (avant == null) return;
        apresChangement();
        bandeau.montrer(e.nom + " (" + nombre(e.note) + "/" + nombre(e.bareme) + ") supprimée", Theme.TEXT_PRIMARY,
                "Annuler", () -> {
                    Stockage.restaurer(avant);
                    apresChangement();
                });
    }

    /** Après chaque modification : affichage tout de suite, BDD ensuite en arrière-plan. */
    static void apresChangement() {
        rafraichir();
        Bdd.planifier(Database::synchroniser, Note::rafraichir);
    }

    // ---------------------------------------------------------------------
    // Cartes du haut : moyenne générale, UE
    // ---------------------------------------------------------------------
    static JPanel carteGenerale() {
        Theme.PanneauArrondi carte = Theme.carte(new GridBagLayout(), 22);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;

        JPanel haut = new JPanel(new BorderLayout());
        haut.setOpaque(false);
        haut.add(Theme.surtitre("Moyenne générale"), BorderLayout.WEST);
        haut.add(new Theme.Pastille("Semestre 1", Theme.ACCENT), BorderLayout.EAST);
        carte.add(haut, g);

        valeurGenerale = new JLabel("—");
        valeurGenerale.setFont(Theme.FONT_CHIFFRE);
        JLabel sur = new JLabel(" / 20");
        sur.setFont(Theme.FONT_CHIFFRE_MOYEN.deriveFont(Font.PLAIN));
        sur.setForeground(Theme.TEXT_DISCRET);
        JPanel chiffre = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        chiffre.setOpaque(false);
        chiffre.add(valeurGenerale);
        chiffre.add(sur);
        g.gridy++;
        g.insets = new Insets(10, 0, 8, 0);
        carte.add(chiffre, g);

        barreGenerale = new Theme.Barre(0, Theme.ACCENT, 8);
        g.gridy++;
        g.insets = new Insets(0, 0, 14, 0);
        carte.add(barreGenerale, g);

        pastilleStatut = new Theme.Pastille("Non calculé", Theme.TEXT_DISCRET);
        JPanel ligneStatut = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ligneStatut.setOpaque(false);
        ligneStatut.add(pastilleStatut);
        g.gridy++;
        g.insets = new Insets(0, 0, 10, 0);
        carte.add(ligneStatut, g);

        detailGenerale = Theme.info(" ");
        detailGenerale.setFont(Theme.FONT_PETIT);
        g.gridy++;
        g.weighty = 1;
        g.anchor = GridBagConstraints.SOUTHWEST;
        g.insets = new Insets(0, 0, 0, 0);
        carte.add(detailGenerale, g);
        return carte;
    }

    static JPanel carteUes() {
        Theme.PanneauArrondi carte = Theme.carte(new BorderLayout(0, 14), 20);
        JPanel haut = new JPanel(new BorderLayout());
        haut.setOpaque(false);
        haut.add(Theme.surtitre("Unités d'enseignement"), BorderLayout.WEST);
        JLabel aide = Theme.info("Survole une UE pour voir le détail du calcul");
        aide.setFont(Theme.FONT_PETIT);
        haut.add(aide, BorderLayout.EAST);
        carte.add(haut, BorderLayout.NORTH);
        grilleUe = new JPanel(new GridLayout(2, 3, 12, 12));
        grilleUe.setOpaque(false);
        carte.add(grilleUe, BorderLayout.CENTER);
        return carte;
    }

    static JPanel tuileUe(Programme.Ue ue, Programme.Resultat r) {
        Double m = r.moyennesUe().get(ue.code());
        Color couleur = m == null ? Theme.TEXT_DISCRET : Theme.couleurMoyenne(m);

        Theme.PanneauArrondi t = new Theme.PanneauArrondi(new GridBagLayout(), 14);
        t.setBackground(Theme.SURFACE_ALT);
        t.setBordure(false);
        t.setBorder(new EmptyBorder(12, 14, 12, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;

        JLabel code = new JLabel(ue.code());
        code.setFont(Theme.FONT_BUTTON);
        code.setForeground(Theme.TEXT_DISCRET);
        JLabel valeur = new JLabel(moyenne(m));
        valeur.setFont(Theme.FONT_CHIFFRE_MOYEN);
        valeur.setForeground(m == null ? Theme.TEXT_DISCRET : couleur);
        valeur.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel haut = new JPanel(new BorderLayout());
        haut.setOpaque(false);
        haut.add(code, BorderLayout.WEST);
        haut.add(valeur, BorderLayout.EAST);
        t.add(haut, g);

        g.gridy++;
        g.insets = new Insets(6, 0, 8, 0);
        t.add(new Theme.Barre(m == null ? 0 : m / 20, couleur, 5), g);

        JLabel nom = new JLabel(ue.nom());
        nom.setFont(Theme.FONT_PETIT);
        nom.setForeground(Theme.TEXT_DISCRET);
        nom.setPreferredSize(new Dimension(60, nom.getPreferredSize().height)); // coupé avec « ... » si trop long
        g.gridy++;
        g.insets = new Insets(0, 0, 0, 0);
        t.add(nom, g);

        String detail = detailUeHtml(ue, r);
        t.setToolTipText(detail);
        for (Component c : new Component[]{haut, code, valeur, nom}) ((JComponent) c).setToolTipText(detail);
        return t;
    }

    /** Infobulle : chaque matière de l'UE, sa moyenne, son coefficient, et le résultat. */
    static String detailUeHtml(Programme.Ue ue, Programme.Resultat r) {
        String discret = Theme.hex(Theme.TEXT_DISCRET);
        StringBuilder sb = new StringBuilder("<html><div style='padding:2px'>");
        sb.append("<b>").append(ue.code()).append(" · ").append(Main.echapper(ue.nom())).append("</b><br>");
        sb.append("<table cellspacing=0 cellpadding=2 style='margin-top:6px'>");
        sb.append("<tr style='color:").append(discret).append("'><td>Matière</td><td align=right>Moy.</td>"
                + "<td align=right>Coef.</td><td align=right>Produit</td></tr>");
        double somme = 0;
        int coefs = 0, coefsTotal = 0;
        for (Programme.LigneUe l : r.detailUe(ue.code())) {
            coefsTotal += l.coef();
            boolean compte = l.moyenne() != null;
            String style = compte ? "" : " style='color:" + discret + "'";
            Programme.Matiere m = Programme.matiere(l.code());
            sb.append("<tr").append(style).append("><td>").append(l.code()).append(" ")
              .append(Main.echapper(m == null ? "" : raccourcir(m.nom(), 26))).append("</td><td align=right>")
              .append(compte ? moyenne(l.moyenne()) : "—").append("</td><td align=right>").append(l.coef())
              .append("</td><td align=right>").append(compte ? moyenne(l.moyenne() * l.coef()) : "—").append("</td></tr>");
            if (compte) {
                somme += l.moyenne() * l.coef();
                coefs += l.coef();
            }
        }
        sb.append("</table><div style='margin-top:6px'>");
        Double m = r.moyennesUe().get(ue.code());
        if (m == null) {
            sb.append("Aucune note dans cette UE pour l'instant.");
        } else {
            sb.append(moyenne(somme)).append(" ÷ ").append(coefs).append(" = <b>").append(moyenne(m)).append("</b>");
            if (coefs < coefsTotal) {
                sb.append("<br><span style='color:").append(discret).append("'>Calcul sur ").append(coefs).append(" coef. sur ")
                  .append(coefsTotal).append(" (les matières sans note sont ignorées)</span>");
            }
        }
        sb.append("</div></div></html>");
        return sb.toString();
    }

    static String raccourcir(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---------------------------------------------------------------------
    // Listes des ressources et des SAÉ (accordéon)
    // ---------------------------------------------------------------------
    static JPanel carteListe(String titre, String sousTitre, JPanel liste) {
        Theme.PanneauArrondi carte = Theme.carte(new BorderLayout(0, 12), 18);
        JPanel haut = new JPanel(new GridLayout(2, 1, 0, 2));
        haut.setOpaque(false);
        JLabel t = new JLabel(titre);
        t.setFont(Theme.FONT_SECTION);
        t.setForeground(Theme.TEXT_PRIMARY);
        JLabel s = Theme.info(sousTitre);
        s.setFont(Theme.FONT_PETIT);
        haut.add(t);
        haut.add(s);
        carte.add(haut, BorderLayout.NORTH);
        carte.add(liste, BorderLayout.CENTER);
        return carte;
    }

    static JPanel lignesMatieres(List<Programme.Matiere> matieres, Map<String, List<Evaluation>> parMatiere,
                                 Programme.Resultat r) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 8, 0);
        for (Programme.Matiere m : matieres) {
            p.add(ligneMatiere(m, parMatiere.getOrDefault(m.code(), List.of()), r.moyennesMatieres().get(m.code())), g);
            g.gridy++;
        }
        return p;
    }

    static JPanel ligneMatiere(Programme.Matiere m, List<Evaluation> evals, Double moy) {
        boolean ouverte = ouvertes.contains(m.code());
        Color couleur = moy == null ? Theme.BORDER : Theme.couleurMoyenne(moy);

        Theme.PanneauArrondi bloc = new Theme.PanneauArrondi(new BorderLayout(), 14);
        bloc.setBackground(Theme.SURFACE_ALT);
        bloc.setBordure(false);
        bloc.setBande(couleur, 4);

        // --- en-tête cliquable ---
        JPanel tete = new JPanel(new BorderLayout(12, 0));
        tete.setOpaque(false);
        tete.setBorder(new EmptyBorder(10, 16, 10, 12));
        tete.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JLabel fleche = new JLabel(ouverte ? Theme.Icone.CHEVRON_BAS : Theme.Icone.CHEVRON_DROITE);
        fleche.setForeground(Theme.TEXT_DISCRET);
        JLabel code = new JLabel(m.code());
        code.setFont(Theme.FONT_BUTTON);
        code.setForeground(Theme.ACCENT);
        code.setPreferredSize(new Dimension(m.sae() ? 70 : 50, code.getPreferredSize().height));
        JPanel gauche = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        gauche.setOpaque(false);
        gauche.add(fleche);
        gauche.add(code);

        JPanel milieu = new JPanel(new GridLayout(2, 1, 0, 1));
        milieu.setOpaque(false);
        JLabel nom = new JLabel(m.nom());
        nom.setFont(Theme.FONT_TABLE.deriveFont(Font.BOLD));
        nom.setForeground(Theme.TEXT_PRIMARY);
        nom.setPreferredSize(new Dimension(80, nom.getPreferredSize().height));
        nom.setToolTipText(m.nom());
        int n = evals.size();
        JLabel nb = new JLabel(n == 0 ? "Aucune note" : n + " note" + (n > 1 ? "s" : ""));
        nb.setFont(Theme.FONT_PETIT);
        nb.setForeground(Theme.TEXT_DISCRET);
        milieu.add(nom);
        milieu.add(nb);

        JPanel droite = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        droite.setOpaque(false);
        Theme.Barre barre = new Theme.Barre(moy == null ? 0 : moy / 20, couleur);
        barre.setPreferredSize(new Dimension(70, 10));
        droite.add(barre);
        JLabel valeur = new JLabel(moyenne(moy));
        valeur.setFont(Theme.FONT_BUTTON.deriveFont(15f));
        valeur.setForeground(moy == null ? Theme.TEXT_DISCRET : couleur);
        valeur.setHorizontalAlignment(SwingConstants.RIGHT);
        valeur.setPreferredSize(new Dimension(48, valeur.getPreferredSize().height));
        droite.add(valeur);

        tete.add(gauche, BorderLayout.WEST);
        tete.add(milieu, BorderLayout.CENTER);
        tete.add(droite, BorderLayout.EAST);
        tete.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { bloc.setBackground(Theme.SURFACE_HOVER); }
            @Override public void mouseExited(MouseEvent e) { bloc.setBackground(Theme.SURFACE_ALT); }
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (!ouvertes.remove(m.code())) ouvertes.add(m.code());
                rafraichir();
            }
        });
        bloc.add(tete, BorderLayout.NORTH);

        // --- détail : notes + UE concernées ---
        if (ouverte) {
            JPanel detail = new JPanel(new GridBagLayout());
            detail.setOpaque(false);
            detail.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                    new EmptyBorder(8, 18, 10, 12)));
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = 0;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            if (evals.isEmpty()) {
                JLabel vide = Theme.info("Pas encore de note : ajoute-la avec le formulaire au-dessus.");
                vide.setFont(Theme.FONT_PETIT);
                detail.add(vide, g);
                g.gridy++;
            }
            for (Evaluation e : evals) {
                detail.add(ligneEvaluation(e), g);
                g.gridy++;
            }
            StringBuilder ues = new StringBuilder("Compte dans ");
            boolean premier = true;
            for (Map.Entry<String, Integer> u : Programme.uesDe(m.code()).entrySet()) {
                if (!premier) ues.append("  ·  ");
                ues.append(u.getKey()).append(" (coef ").append(u.getValue()).append(")");
                premier = false;
            }
            JLabel lUes = Theme.info(ues.toString());
            lUes.setFont(Theme.FONT_PETIT);
            g.insets = new Insets(8, 0, 0, 0);
            detail.add(lUes, g);
            bloc.add(detail, BorderLayout.CENTER);
        }
        return bloc;
    }

    static JPanel ligneEvaluation(Evaluation e) {
        JPanel l = new JPanel(new BorderLayout(10, 0));
        l.setOpaque(false);
        l.setBorder(new EmptyBorder(4, 0, 4, 0));

        JLabel nom = new JLabel(e.nom);
        nom.setFont(Theme.FONT_TABLE);
        nom.setForeground(Theme.TEXT_PRIMARY);
        nom.setPreferredSize(new Dimension(60, nom.getPreferredSize().height));
        nom.setToolTipText(e.nom + (e.statut.equals(Stockage.SYNC) ? "" : "  (pas encore envoyée à la BDD)"));

        JPanel droite = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        droite.setOpaque(false);
        JLabel note = new JLabel(nombre(e.note) + " / " + nombre(e.bareme));
        note.setFont(Theme.FONT_TABLE.deriveFont(Font.BOLD));
        note.setForeground(e.surVingt() >= 10 ? Theme.SUCCES : Theme.RETARD);
        JLabel coef = new JLabel("coef " + nombre(e.coef));
        coef.setFont(Theme.FONT_PETIT);
        coef.setForeground(Theme.TEXT_DISCRET);
        coef.setBorder(new EmptyBorder(0, 6, 0, 4));
        JButton modif = Theme.boutonIcone(Theme.Icone.CRAYON, "Modifier", Theme.ACCENT);
        modif.addActionListener(x -> modifier(e));
        JButton suppr = Theme.boutonIcone(Theme.Icone.CROIX, "Supprimer", Theme.RETARD);
        suppr.addActionListener(x -> supprimer(e));
        droite.add(note);
        droite.add(coef);
        droite.add(modif);
        droite.add(suppr);

        l.add(nom, BorderLayout.CENTER);
        l.add(droite, BorderLayout.EAST);
        return l;
    }

    // ---------------------------------------------------------------------
    // Rafraîchissement (lecture du fichier local : instantané)
    // ---------------------------------------------------------------------
    static void rafraichir() {
        if (fenetre == null || grilleUe == null) return;
        List<Evaluation> evals = Stockage.visibles();
        Programme.Resultat r = Programme.calculer(evals);

        Double gen = r.generale();
        valeurGenerale.setText(moyenne(gen));
        valeurGenerale.setForeground(gen == null ? Theme.TEXT_DISCRET : Theme.couleurMoyenne(gen));
        barreGenerale.changer(gen == null ? 0 : gen / 20, gen == null ? Theme.BORDER : Theme.couleurMoyenne(gen));
        pastilleStatut.changer(r.statut().libelle, couleurStatut(r.statut()));
        long nbUe = r.moyennesUe().values().stream().filter(v -> v != null).count();
        detailGenerale.setText(r.nbEvaluations() + " évaluation" + (r.nbEvaluations() > 1 ? "s" : "")
                + "  ·  " + nbUe + "/" + Programme.UES.size() + " UE calculées");

        grilleUe.removeAll();
        for (Programme.Ue ue : Programme.UES) grilleUe.add(tuileUe(ue, r));

        Map<String, List<Evaluation>> parMatiere = new HashMap<>();
        for (Evaluation e : evals) parMatiere.computeIfAbsent(e.code, k -> new ArrayList<>()).add(e);
        remplacer(listeRessources, lignesMatieres(Programme.RESSOURCES, parMatiere, r));
        remplacer(listeSaes, lignesMatieres(Programme.SAES, parMatiere, r));

        majSynchro();
        fenetre.getContentPane().revalidate();
        fenetre.getContentPane().repaint();
    }

    static void remplacer(JPanel conteneur, JComponent contenu) {
        conteneur.removeAll();
        conteneur.add(contenu, BorderLayout.CENTER);
    }

    /** Pastille d'état de la synchronisation, en haut à droite. */
    static void majSynchro() {
        if (pastilleSynchro == null) return;
        int attente = Stockage.enAttente();
        switch (Bdd.etat()) {
            case LOCAL -> {
                pastilleSynchro.changer("Enregistré sur cet ordinateur", Theme.TEXT_DISCRET);
                pastilleSynchro.setToolTipText("Aucune base de données configurée (Paramètres) : tout reste sur cet ordinateur.");
            }
            case EN_LIGNE -> {
                pastilleSynchro.changer(attente == 0 ? "●  Synchronisé" : "●  Envoi de " + attente + " changement(s)…",
                        attente == 0 ? Theme.SUCCES : Theme.ACCENT);
                pastilleSynchro.setToolTipText("Base de données joignable. Clique pour synchroniser maintenant.");
            }
            case HORS_LIGNE -> {
                pastilleSynchro.changer("●  Hors ligne" + (attente > 0 ? " · " + attente + " en attente" : ""), Theme.ATTENTION);
                pastilleSynchro.setToolTipText("<html>Base de données injoignable : les notes sont enregistrées sur cet ordinateur<br>"
                        + "et seront envoyées automatiquement au retour de la connexion. Clique pour réessayer.</html>");
            }
            default -> {
                pastilleSynchro.changer("●  Connexion…", Theme.TEXT_DISCRET);
                pastilleSynchro.setToolTipText("Connexion à la base de données en cours.");
            }
        }
    }

    // =====================================================================
    // FENÊTRE
    // =====================================================================
    public static void main(String[] args) {
        Theme.installer();

        // L'affichage vient du fichier local : la fenêtre s'ouvre tout de suite, BDD ou pas
        SwingUtilities.invokeLater(() -> {
            JFrame frame = construireFenetre();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            if (!Stockage.nonMigrees.isEmpty()) {
                bandeau.montrer("Matières de l'ancienne version non rattachées : " + String.join(", ", Stockage.nonMigrees)
                        + " (gardées dans notes_backup.csv)", Theme.ATTENTION, null, null);
            }

            // Synchronisation en arrière-plan : la fenêtre est déjà utilisable
            Bdd.planifier(Database::synchroniser, Note::rafraichir);
        });
    }

    /** Construit la fenêtre des notes (sans l'afficher). */
    static JFrame construireFenetre() {
        JFrame frame = new JFrame("Centrale Étudiant · Notes");
        frame.setSize(1180, 880);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setDefaultCloseOperation(fermeture);
        frame.getContentPane().setBackground(Theme.BACKGROUND);
        fenetre = frame;

        pastilleSynchro = new Theme.Pastille(" ", Theme.TEXT_DISCRET);
        pastilleSynchro.setCursor(new Cursor(Cursor.HAND_CURSOR));
        pastilleSynchro.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                Bdd.reessayer();
                pastilleSynchro.changer("●  Synchronisation…", Theme.ACCENT);
                Bdd.planifier(Database::synchroniser, Note::rafraichir);
            }
        });
        JPanel entete = Theme.entete("Notes et moyennes",
                Theme.info("BUT1 Informatique · Semestre 1 · calcul officiel par UE"), pastilleSynchro);

        Colonne contenu = new Colonne();
        contenu.setBorder(new EmptyBorder(4, 24, 24, 24));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.fill = GridBagConstraints.BOTH;
        g.insets = new Insets(0, 0, 16, 0);

        // ligne 1 : moyenne générale + UE
        JPanel haut = new JPanel(new GridBagLayout());
        haut.setOpaque(false);
        GridBagConstraints h = new GridBagConstraints();
        h.gridy = 0;
        h.fill = GridBagConstraints.BOTH;
        h.weighty = 1;
        h.gridx = 0;
        h.weightx = 0.32;
        h.insets = new Insets(0, 0, 0, 16);
        JPanel generale = carteGenerale();
        generale.setPreferredSize(new Dimension(300, 10));
        haut.add(generale, h);
        h.gridx = 1;
        h.weightx = 0.68;
        h.insets = new Insets(0, 0, 0, 0);
        haut.add(carteUes(), h);
        contenu.add(haut, g);

        // ligne 2 : ajout
        g.gridy++;
        contenu.add(carteAjout(), g);

        // ligne 3 : ressources | SAÉ
        listeRessources = new JPanel(new BorderLayout());
        listeRessources.setOpaque(false);
        listeSaes = new JPanel(new BorderLayout());
        listeSaes.setOpaque(false);
        JPanel bas = new JPanel(new GridBagLayout());
        bas.setOpaque(false);
        GridBagConstraints b = new GridBagConstraints();
        b.gridy = 0;
        b.fill = GridBagConstraints.HORIZONTAL;
        b.anchor = GridBagConstraints.NORTH;
        b.weightx = 0.5;
        b.weighty = 1;
        b.gridx = 0;
        b.insets = new Insets(0, 0, 0, 16);
        bas.add(carteListe("Ressources", "R1.01 à R1.12 · clique sur une matière pour voir ses notes", listeRessources), b);
        b.gridx = 1;
        b.insets = new Insets(0, 0, 0, 0);
        bas.add(carteListe("SAÉ", "Situations d'apprentissage et d'évaluation · 1.01 à 1.06", listeSaes), b);
        g.gridy++;
        g.weighty = 1;
        g.anchor = GridBagConstraints.NORTH;
        g.fill = GridBagConstraints.HORIZONTAL;
        contenu.add(bas, g);

        JScrollPane scroll = new JScrollPane(contenu);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);

        bandeau = new Bandeau();

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.BACKGROUND);
        panel.add(entete, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bandeau, BorderLayout.SOUTH);
        frame.setContentPane(panel);

        rafraichir();
        Theme.auChangement(Note::rafraichir);         // infobulles HTML recolorées
        Bdd.auChangement(Note::majSynchro);
        return frame;
    }
}
