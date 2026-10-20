package com.centrale;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;

public class Note {

    // =====================================================================
    // CONNEXION BASE DE DONNÉES
    // =====================================================================
    // Base facultative : adresse et identifiants dans les Paramètres (voir Config).
    // Sans base configurée, tout reste dans le fichier CSV local.

    static int[] columnNote = {2};
    static JTable tableNote;
    static JTable tableMoyenne;
    static JLabel labelGenerale;
    static String[] nomsMatieres = new String[0];

    // =====================================================================
    // MIROIR CSV LOCAL (2e TÉMOIN DES NOTES)
    //
    // Fichier notes_backup.csv, une ligne par matière ou par note :
    //     type;matiere;valeur;quotient;coefficient;statut
    //   type   : M (matière) ou N (note)
    //   coefficient : ligne M = poids de la matière dans la moyenne générale
    //                 ligne N = poids de la note dans la moyenne de la matière (1 par défaut)
    //   statut : SYNC = présent dans la BDD ET dans le fichier
    //            ADD  = ajouté hors-ligne, pas encore envoyé à la BDD
    //            DEL  = supprimé hors-ligne, pas encore supprimé de la BDD
    //            MOD  = coefficient de matière changé hors-ligne, pas encore envoyé à la BDD
    //
    // Règles :
    //  - chaque ajout/suppression met à jour la BDD ET le CSV en même temps ;
    //  - BDD injoignable : le CSV sert de témoin (affichage + modifications) ;
    //  - retour en ligne : s'il n'y a rien d'en attente dans le CSV, il est
    //    réécrit à partir de la BDD ; sinon les changements en attente sont
    //    appliqués à la BDD, puis le CSV est réaligné sur la BDD.
    // =====================================================================
    static final class CsvMirror {
        static final Path FILE = Config.fichier("notes_backup.csv");
        static final String HEADER = "type;matiere;valeur;quotient;coefficient;statut";
        static final String SYNC = "SYNC";
        static final String ADD = "ADD";
        static final String DEL = "DEL";
        static final String MOD = "MOD";

        static final class Entry {
            final String type;
            final String matiere;
            final int valeur;
            final int quotient;
            final double coefficient;
            String statut;

            Entry(String type, String matiere, int valeur, int quotient, double coefficient, String statut) {
                this.type = type;
                this.matiere = matiere;
                this.valeur = valeur;
                this.quotient = quotient;
                this.coefficient = coefficient;
                this.statut = statut;
            }

            boolean estNote() {
                return type.equals("N");
            }

            /** coef == null : le coefficient est ignoré dans la recherche. */
            boolean memeNote(String m, int v, int q, Double coef) {
                return estNote() && matiere.equals(m) && valeur == v && quotient == q
                        && (coef == null || Math.abs(coefficient - coef) < 0.001);
            }

            String toLine() {
                return type + ";" + matiere + ";" + valeur + ";" + quotient + ";"
                        + formatCoef(coefficient) + ";" + statut;
            }
        }

        /** Lit le CSV. Si la lecture échoue, on lève une exception plutôt que d'écraser le fichier ensuite. */
        static synchronized List<Entry> lire() {
            List<Entry> res = new ArrayList<>();
            Path p = FILE;
            if (!Files.exists(p)) return res;
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String[] parts = line.split(";");
                    if (parts[0].equals("type") || (parts.length != 5 && parts.length != 6)) continue;
                    try {
                        // 5 colonnes = ancien format (sans coefficient) -> coefficient 1
                        boolean ancien = (parts.length == 5);
                        double coef = ancien ? 1.0 : Double.parseDouble(parts[4]);
                        String statut = ancien ? parts[4] : parts[5];
                        res.add(new Entry(parts[0], parts[1],
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), coef, statut));
                    } catch (NumberFormatException ignored) {
                        // ligne illisible : ignorée
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Lecture de " + FILE + " impossible", e);
            }
            return res;
        }

        /** Écriture atomique (fichier temporaire puis remplacement) pour ne jamais laisser un CSV à moitié écrit. */
        static synchronized void ecrire(List<Entry> entries) {
            List<String> lignes = new ArrayList<>();
            lignes.add(HEADER);
            for (Entry e : entries) lignes.add(e.toLine());
            try {
                Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
                Files.write(tmp, lignes, StandardCharsets.UTF_8);
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("Erreur d'écriture de " + FILE + " : " + e.getMessage());
            }
        }

        // ---------- Mises à jour appelées en même temps que la BDD ----------

        static synchronized void ajouterMatiere(String nom, double coefficient, boolean dbOk) {
            List<Entry> l = lire();
            l.add(new Entry("M", nom, 0, 0, coefficient, dbOk ? SYNC : ADD));
            ecrire(l);
        }

        /**
         * Renomme une matière (ses notes suivent) et change son coefficient.
         * Hors-ligne, un coefficient déjà synchronisé passe en MOD (envoyé à la BDD plus tard).
         */
        static synchronized void modifierMatiere(String ancienNom, String nouveauNom, double coefficient, boolean dbOk) {
            List<Entry> l = lire();
            for (int i = 0; i < l.size(); i++) {
                Entry e = l.get(i);
                if (!e.matiere.equals(ancienNom)) continue;
                if (e.type.equals("M")) {
                    boolean coefChange = Math.abs(e.coefficient - coefficient) >= 0.001;
                    String statut = (!dbOk && coefChange && e.statut.equals(SYNC)) ? MOD : e.statut;
                    l.set(i, new Entry("M", nouveauNom, 0, 0, coefficient, statut));
                } else {
                    l.set(i, new Entry(e.type, nouveauNom, e.valeur, e.quotient, e.coefficient, e.statut));
                }
            }
            ecrire(l);
        }

        /** Retire la matière et toutes ses notes du fichier. */
        static synchronized void supprimerMatiere(String nom) {
            List<Entry> l = lire();
            l.removeIf(e -> e.matiere.equals(nom));
            ecrire(l);
        }

        static synchronized void ajouterNote(String matiere, int valeur, int quotient, double coefficient, boolean dbOk) {
            List<Entry> l = lire();
            l.add(new Entry("N", matiere, valeur, quotient, coefficient, dbOk ? SYNC : ADD));
            ecrire(l);
        }

        /**
         * Supprime la note la plus récente correspondante (comme la BDD).
         * Hors-ligne, une note déjà synchronisée est marquée DEL (à supprimer de la BDD plus tard),
         * une note ajoutée hors-ligne (ADD) est simplement retirée du fichier.
         * @param coefficient null = le coefficient est ignoré dans la recherche
         * @return true si la note existait dans le fichier
         */
        static synchronized boolean supprimerNote(String matiere, int valeur, int quotient, Double coefficient, boolean dbOk) {
            List<Entry> l = lire();
            for (int i = l.size() - 1; i >= 0; i--) {
                Entry e = l.get(i);
                if (e.memeNote(matiere, valeur, quotient, coefficient) && !e.statut.equals(DEL)) {
                    if (dbOk || e.statut.equals(ADD)) l.remove(i);
                    else e.statut = DEL;
                    ecrire(l);
                    return true;
                }
            }
            return false;
        }

        // ---------- Lecture (utilisée pour l'affichage) ----------

        static synchronized boolean matiereExiste(String nom) {
            for (Entry e : lire()) {
                if (e.type.equals("M") && e.matiere.equalsIgnoreCase(nom)) return true;
            }
            return false;
        }

        static synchronized List<String> listerMatieres() {
            List<String> noms = new ArrayList<>();
            for (Entry e : lire()) {
                if (e.type.equals("M")) noms.add(e.matiere);
            }
            return noms;
        }

        /** Coefficient de chaque matière, dans l'ordre d'ajout (1 si inconnu). */
        static synchronized Map<String, Double> coefficientsMatieres() {
            Map<String, Double> coefs = new LinkedHashMap<>();
            for (Entry e : lire()) {
                if (e.type.equals("M")) coefs.put(e.matiere, e.coefficient > 0 ? e.coefficient : 1.0);
            }
            return coefs;
        }

        static synchronized List<String> listerNotes(String matiere) {
            List<String> notes = new ArrayList<>();
            for (Entry e : lire()) {
                if (e.estNote() && e.matiere.equals(matiere) && !e.statut.equals(DEL)) {
                    notes.add(e.valeur + "/" + e.quotient + " (coef " + formatCoef(e.coefficient) + ")");
                }
            }
            return notes;
        }

        // ---------- Comparaison / synchronisation au retour en ligne ----------

        static synchronized void reconcilier() {
            List<Entry> l = lire();

            boolean enAttente = false;
            for (Entry e : l) {
                if (!e.statut.equals(SYNC)) { enAttente = true; break; }
            }

            if (enAttente) {
                boolean toutOk = true;

                // 1) les matières créées ou modifiées hors-ligne d'abord
                for (Entry e : l) {
                    if (!e.type.equals("M") || e.statut.equals(SYNC)) continue;
                    boolean ok;
                    if (MatiereDAO.trouverIdParNom(e.matiere) == null) {
                        ok = MatiereDAO.inserer(e.matiere, e.coefficient) != -1;
                    } else {
                        ok = MatiereDAO.modifier(e.matiere, e.matiere, e.coefficient);
                    }
                    if (ok) e.statut = SYNC; else toutOk = false;
                }

                // 2) puis les notes ajoutées / supprimées hors-ligne
                Iterator<Entry> it = l.iterator();
                while (it.hasNext()) {
                    Entry e = it.next();
                    if (!e.estNote() || e.statut.equals(SYNC)) continue;

                    Integer id = MatiereDAO.trouverIdParNom(e.matiere);
                    if (id == null) { toutOk = false; continue; }

                    if (e.statut.equals(ADD)) {
                        if (NoteDAO.inserer(id, e.valeur, e.quotient, e.coefficient)) e.statut = SYNC;
                        else toutOk = false;
                    } else if (e.statut.equals(DEL)) {
                        if (NoteDAO.supprimer(id, e.valeur, e.quotient, e.coefficient) >= 0) it.remove();
                        else toutOk = false;
                    }
                }

                // On enregistre ce qui a pu être envoyé ; le reste reste en attente
                ecrire(l);
                if (!toutOk) return; // on ne touche pas au CSV tant que la BDD n'est pas à jour
            }

            // Plus rien en attente : le CSV est réaligné sur la BDD
            reconstruireDepuisBD();
        }

        /** Réécrit le CSV à partir de la BDD. Si la lecture BDD échoue, le CSV existant n'est pas touché. */
        static synchronized void reconstruireDepuisBD() {
            String sql = "SELECT m.nom, m.coefficient AS coef_matiere, n.valeur, n.quotient, n.coefficient FROM matieres m "
                       + "LEFT JOIN notes n ON n.matiere_id = m.id ORDER BY m.id, n.id";
            List<Entry> l = new ArrayList<>();
            String derniere = null;
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String nom = rs.getString("nom");
                    if (!nom.equals(derniere)) {
                        l.add(new Entry("M", nom, 0, 0, rs.getDouble("coef_matiere"), SYNC));
                        derniere = nom;
                    }
                    int valeur = rs.getInt("valeur");
                    if (!rs.wasNull()) {
                        l.add(new Entry("N", nom, valeur, rs.getInt("quotient"), rs.getDouble("coefficient"), SYNC));
                    }
                }
            } catch (SQLException e) {
                System.err.println("Lecture BDD impossible, CSV conservé tel quel : " + e.getMessage());
                return;
            }
            ecrire(l);
        }
    }

    // =====================================================================
    // ACCÈS BASE DE DONNÉES
    // =====================================================================
    static final class Database {

        /** true une fois les tables (et la colonne coefficient) vérifiées/créées. */
        static boolean schemaPret = false;

        static Connection getConnection() throws SQLException {
            if (!Config.bddConfiguree()) throw new SQLException("Aucune base de données configurée (mode local)");
            return DriverManager.getConnection(Config.get(Config.DB_URL), Config.get(Config.DB_USER), Config.get(Config.DB_PASSWORD));
        }

        /** true si la BDD répond. */
        static boolean ping() {
            try (Connection conn = getConnection()) {
                return true;
            } catch (SQLException e) {
                return false;
            }
        }

        /** Si la BDD est joignable : compare BDD et CSV, et aligne l'un sur l'autre (voir CsvMirror). */
        static void synchroniser() {
            if (!ping()) return;
            if (!schemaPret) creerTables(); // appli lancée hors-ligne : on prépare la BDD dès son retour
            try {
                CsvMirror.reconcilier();
            } catch (Exception e) {
                System.err.println("Synchronisation BDD/CSV impossible : " + e.getMessage());
            }
        }

        static boolean connecter() {
            try (Connection conn = getConnection()) {
                System.out.println("Connexion à la base de données réussie !");
                return true;
            } catch (SQLException e) {
                System.err.println("Base de données injoignable, mode local : " + e.getMessage());
                return false;
            }
        }

        static void creerTables() {
            String sqlMatieres = "CREATE TABLE IF NOT EXISTS matieres (" +
                    "id SERIAL PRIMARY KEY," +
                    "nom VARCHAR(100) NOT NULL UNIQUE," +
                    "coefficient NUMERIC(4,2) NOT NULL DEFAULT 1" +
                    ")";

            String sqlNotes = "CREATE TABLE IF NOT EXISTS notes (" +
                    "id SERIAL PRIMARY KEY," +
                    "matiere_id INTEGER NOT NULL REFERENCES matieres(id) ON DELETE CASCADE," +
                    "valeur INTEGER NOT NULL," +
                    "quotient INTEGER NOT NULL," +
                    "coefficient NUMERIC(4,2) NOT NULL DEFAULT 1," +
                    "date_creation TIMESTAMP DEFAULT NOW()" +
                    ")";

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(sqlMatieres);
                stmt.execute(sqlNotes);
                // Base déjà existante sans la colonne : on l'ajoute (les anciennes notes valent coefficient 1)
                stmt.execute("ALTER TABLE notes ADD COLUMN IF NOT EXISTS coefficient NUMERIC(4,2) NOT NULL DEFAULT 1");
                stmt.execute("ALTER TABLE matieres ADD COLUMN IF NOT EXISTS coefficient NUMERIC(4,2) NOT NULL DEFAULT 1");
                System.out.println("Tables 'matieres' et 'notes' prêtes.");
                schemaPret = true;
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    // =====================================================================
    // DAO MATIÈRES
    // =====================================================================
    static final class MatiereDAO {

        static Integer trouverIdParNom(String nom) {
            String sql = "SELECT id FROM matieres WHERE nom = ?";
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, nom);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getInt("id");
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return null;
        }

        static int inserer(String nom, double coefficient) {
            String sql = "INSERT INTO matieres (nom, coefficient) VALUES (?, ?) RETURNING id";
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, nom);
                stmt.setBigDecimal(2, toDecimal(coefficient));
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getInt("id");
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return -1;
        }

        /** Renomme une matière et change son coefficient. false si la BDD a refusé (nom déjà pris, injoignable...). */
        static boolean modifier(String ancienNom, String nouveauNom, double coefficient) {
            String sql = "UPDATE matieres SET nom = ?, coefficient = ? WHERE nom = ?";
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, nouveauNom);
                stmt.setBigDecimal(2, toDecimal(coefficient));
                stmt.setString(3, ancienNom);
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        /** Supprime la matière et ses notes (ON DELETE CASCADE). */
        static boolean supprimer(String nom) {
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM matieres WHERE nom = ?")) {
                stmt.setString(1, nom);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        static List<String> listerNoms() {
            List<String> noms = new ArrayList<>();
            String sql = "SELECT nom FROM matieres ORDER BY id";
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) noms.add(rs.getString("nom"));
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return noms;
        }
    }

    // =====================================================================
    // DAO NOTES (AVEC SUPPRESSION ET INSERTION)
    // =====================================================================
    static final class NoteDAO {

        static boolean inserer(int matiereId, int valeur, int quotient, double coefficient) {
            String sql = "INSERT INTO notes (matiere_id, valeur, quotient, coefficient) VALUES (?, ?, ?, ?)";
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, matiereId);
                stmt.setInt(2, valeur);
                stmt.setInt(3, quotient);
                stmt.setBigDecimal(4, toDecimal(coefficient));
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        /**
         * Supprime la note la plus récente correspondant aux critères (coefficient null = ignoré).
         * @return 1 = supprimée, 0 = note introuvable en BDD, -1 = erreur (BDD injoignable)
         */
        static int supprimer(int matiereId, int valeur, int quotient, Double coefficient) {
            String sql = "DELETE FROM notes WHERE id IN (" +
                         "  SELECT id FROM notes WHERE matiere_id = ? AND valeur = ? AND quotient = ? " +
                         (coefficient != null ? "AND coefficient = ? " : "") +
                         "  ORDER BY id DESC LIMIT 1" +
                         ")";
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, matiereId);
                stmt.setInt(2, valeur);
                stmt.setInt(3, quotient);
                if (coefficient != null) stmt.setBigDecimal(4, toDecimal(coefficient));
                return stmt.executeUpdate() > 0 ? 1 : 0;
            } catch (SQLException e) {
                e.printStackTrace();
                return -1;
            }
        }

        static List<String> listerPourMatiere(int matiereId) {
            List<String> resultats = new ArrayList<>();
            String sql = "SELECT valeur, quotient FROM notes WHERE matiere_id = ? ORDER BY id";
            try (Connection conn = Database.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, matiereId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        resultats.add(rs.getInt("valeur") + "/" + rs.getInt("quotient"));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return resultats;
        }
    }

    // =====================================================================
    // UTILITAIRES & THEME
    // =====================================================================
    static JButton themedButton(String text) {
        return Theme.bouton(text);
    }

    static class NoteFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            replace(fb, offset, 0, string, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            String currentText = fb.getDocument().getText(0, fb.getDocument().getLength());
            String proposedText = currentText.substring(0, offset) + text + currentText.substring(offset + length);

            if (proposedText.matches("\\d*(/\\d*)?")) {
                super.replace(fb, offset, length, text, attrs);
            }
        }
    }

    /** Coefficient : 2 chiffres max avant et après la virgule ou le point (ex : 2, 0.5, 1,5). */
    static class CoefFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            replace(fb, offset, 0, string, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            String currentText = fb.getDocument().getText(0, fb.getDocument().getLength());
            String proposedText = currentText.substring(0, offset) + text + currentText.substring(offset + length);

            if (proposedText.matches("\\d{0,2}([.,]\\d{0,2})?")) {
                super.replace(fb, offset, length, text, attrs);
            }
        }
    }

    /** Lit un coefficient saisi ("1,5" ou "1.5"), arrondi à 2 décimales. null si vide ou invalide. */
    static Double parseCoef(String saisie) {
        if (saisie == null) return null;
        String t = saisie.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        try {
            return Math.round(Double.parseDouble(t) * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 2.0 -> "2", 0.5 -> "0.5" (toujours avec un point : sûr pour le CSV). */
    static String formatCoef(double c) {
        if (c == Math.rint(c)) return String.valueOf((long) c);
        return String.valueOf(c);
    }

    static BigDecimal toDecimal(double c) {
        return BigDecimal.valueOf(c).setScale(2, RoundingMode.HALF_UP);
    }

    static void ListMatiere() {
        DefaultTableModel model = (DefaultTableModel) tableNote.getModel();
        nomsMatieres = new String[model.getRowCount()];
        for (int i = 0; i < model.getRowCount(); i++) {
            nomsMatieres[i] = (String) model.getValueAt(i, 0);
        }
    }

    /**
     * Recharge complètement l'affichage de l'application (BDD si joignable, sinon CSV local).
     */
    static void rechargerApplication() {
        chargerDonneesInitiales();

        DefaultTableModel modelNote = new DefaultTableModel(donneesInitiales, colonnesInitiales) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tableNote.setModel(modelNote);

        tableMoyenne.setModel(modeleMoyennes());
    }

    /**
     * Tableau des moyennes (matière, coefficient, moyenne) et mise à jour de la moyenne générale :
     * somme(moyenne x coef de la matière) / somme(coefs), sur les matières qui ont au moins une note.
     */
    static DefaultTableModel modeleMoyennes() {
        ListMatiere();
        List<Double> moyennes = calculMoyenneGenerale();
        Map<String, Double> coefs = CsvMirror.coefficientsMatieres();

        DefaultTableModel modelMoyenne = new DefaultTableModel(new String[]{"Matière", "Coefficient", "Moyenne"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        double sommePonderee = 0, sommeCoefs = 0;
        for (int i = 0; i < nomsMatieres.length; i++) {
            Double m = (i < moyennes.size()) ? moyennes.get(i) : null;
            double coef = coefs.getOrDefault(nomsMatieres[i], 1.0);
            if (m != null) {
                sommePonderee += m * coef;
                sommeCoefs += coef;
            }
            String affichage = (m == null) ? "" : String.format("%.2f", m);
            modelMoyenne.addRow(new Object[]{ nomsMatieres[i], formatCoef(coef), affichage });
        }

        if (labelGenerale != null) {
            if (sommeCoefs > 0) {
                double g = sommePonderee / sommeCoefs;
                labelGenerale.setText(String.format("Moyenne générale : %.2f / 20", g));
                labelGenerale.setForeground(g >= 10 ? Theme.SUCCES : g >= 8 ? Theme.ATTENTION : Theme.RETARD);
            } else {
                labelGenerale.setText("Moyenne générale : —");
                labelGenerale.setForeground(Theme.TEXT_DISCRET);
            }
        }
        return modelMoyenne;
    }

    // =====================================================================
    // SUPPRIMER LA NOTE SÉLECTIONNÉE DANS LE TABLEAU
    // =====================================================================
    static void DeleteNote() {
        java.awt.Component parent = javax.swing.SwingUtilities.getWindowAncestor(tableNote);

        int row = tableNote.getSelectedRow();
        int col = tableNote.getSelectedColumn();
        if (row < 0 || col < 0) {
            JOptionPane.showMessageDialog(parent, "Clique d'abord sur la case de la note à supprimer.", "Aucune sélection", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        DefaultTableModel model = (DefaultTableModel) tableNote.getModel();
        int modelRow = tableNote.convertRowIndexToModel(row);
        int modelCol = tableNote.convertColumnIndexToModel(col);
        Object cellule = model.getValueAt(modelRow, modelCol);
        Matcher m = NOTE_AFFICHEE.matcher(cellule == null ? "" : cellule.toString());
        if (modelCol < 1 || !m.matches()) {
            JOptionPane.showMessageDialog(parent, "La case sélectionnée ne contient pas de note.", "Erreur", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String matiereChoisie = (String) model.getValueAt(modelRow, 0);
        int val = Integer.parseInt(m.group(1));
        int quot = Integer.parseInt(m.group(2));
        Double coef = (m.group(3) == null) ? 1.0 : Double.parseDouble(m.group(3));

        // Remet d'abord BDD et CSV d'accord si la BDD vient de revenir
        Database.synchroniser();

        // 1) BDD : 1 = supprimée, 0 = introuvable, -1 = BDD injoignable
        int res = -1;
        if (Database.ping()) {
            Integer matiereId = MatiereDAO.trouverIdParNom(matiereChoisie);
            if (matiereId != null) res = NoteDAO.supprimer(matiereId, val, quot, coef);
        }
        if (res == 0) {
            JOptionPane.showMessageDialog(parent, "Impossible de trouver cette note en BDD.", "Erreur", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 2) CSV local : mis à jour en même temps, dans tous les cas
        boolean dbOk = (res == 1);
        boolean trouveCsv = CsvMirror.supprimerNote(matiereChoisie, val, quot, coef, dbOk);
        if (!dbOk && !trouveCsv) {
            JOptionPane.showMessageDialog(parent, "Note introuvable dans le fichier local.", "Erreur", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!dbOk && Config.bddConfiguree()) {
            JOptionPane.showMessageDialog(parent, "Connexion BDD indisponible. La note a été supprimée du fichier local.\nElle sera supprimée de la BDD dès la reconnexion.", "Mode Hors-Ligne", JOptionPane.INFORMATION_MESSAGE);
        }
        rechargerApplication();
    }

    // =====================================================================
    // FENÊTRES AJOUT (MATIERE & NOTE)
    // =====================================================================
    static void AddMatiere() {
        JFrame addMatiereFrame = new JFrame("Nouvelle Matière");
        addMatiereFrame.setSize(600, 300);
        addMatiereFrame.setLocationRelativeTo(null);
        addMatiereFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addMatiereFrame.getContentPane().setBackground(Theme.BACKGROUND);

        JTextField matiereField = new JTextField(20);
        styleTextField(matiereField);

        JLabel labelMatiere = new JLabel("Nom de la matière :");
        styleLabel(labelMatiere);

        JTextField coefField = new JTextField("1", 10);
        styleTextField(coefField);
        ((PlainDocument) coefField.getDocument()).setDocumentFilter(new CoefFilter());
        JLabel labelCoef = new JLabel("Coefficient :");
        styleLabel(labelCoef);

        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setBackground(Theme.BACKGROUND);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        centerPanel.add(labelMatiere, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        centerPanel.add(matiereField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE;
        centerPanel.add(labelCoef, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        centerPanel.add(coefField, gbc);

        JButton validButton = themedButton("Valider");
        validButton.addActionListener(e -> {
            String saisie = matiereField.getText().trim();
            if (!nomMatiereValide(addMatiereFrame, saisie)) return;
            Double coefficient = parseCoef(coefField.getText());
            if (coefficient == null || coefficient <= 0) {
                JOptionPane.showMessageDialog(addMatiereFrame, "Coefficient invalide (ex : 1, 2, 0.5)", "Coefficient invalide", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Database.synchroniser();

            if (Database.ping()) {
                if (MatiereDAO.inserer(saisie, coefficient) == -1) {
                    JOptionPane.showMessageDialog(addMatiereFrame, "Erreur lors de l'enregistrement ou matière existante.", "Erreur", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                CsvMirror.ajouterMatiere(saisie, coefficient, true);
            } else {
                if (CsvMirror.matiereExiste(saisie)) {
                    JOptionPane.showMessageDialog(addMatiereFrame, "Cette matière existe déjà.", "Erreur", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                CsvMirror.ajouterMatiere(saisie, coefficient, false);
                if (Config.bddConfiguree()) JOptionPane.showMessageDialog(addMatiereFrame, "Connexion BDD indisponible. La matière a été enregistrée dans le fichier local.\nElle sera envoyée à la BDD dès la reconnexion.", "Mode Hors-Ligne", JOptionPane.INFORMATION_MESSAGE);
            }

            rechargerApplication();
            addMatiereFrame.dispose();
        });

        JButton annulButton = Theme.boutonSecondaire("Annuler");
        annulButton.addActionListener(e -> addMatiereFrame.dispose());

        JPanel btnValidAnnulPanel = new JPanel();
        btnValidAnnulPanel.setBackground(Theme.BACKGROUND);
        btnValidAnnulPanel.add(validButton);
        btnValidAnnulPanel.add(annulButton);

        JPanel newMatierePanel = new JPanel(new BorderLayout());
        newMatierePanel.setBackground(Theme.BACKGROUND);
        newMatierePanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        newMatierePanel.add(centerPanel, BorderLayout.CENTER);
        newMatierePanel.add(btnValidAnnulPanel, BorderLayout.SOUTH);

        addMatiereFrame.getContentPane().add(newMatierePanel);
        addMatiereFrame.setVisible(true);
    }

    static boolean nomMatiereValide(java.awt.Component parent, String nom) {
        if (nom.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Le nom ne peut pas être vide.", "Erreur", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (nom.contains(";")) {
            JOptionPane.showMessageDialog(parent, "Le caractère ';' est interdit dans le nom (séparateur du CSV).", "Erreur", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    // =====================================================================
    // PARAMÈTRES D'UNE MATIÈRE (double-clic sur la matière) : nom, coefficient, suppression
    // =====================================================================
    static void ModifierMatiere(String nom) {
        double coefActuel = CsvMirror.coefficientsMatieres().getOrDefault(nom, 1.0);

        JFrame frame = new JFrame("Paramètres de la matière");
        frame.setSize(600, 320);
        frame.setLocationRelativeTo(fenetre);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().setBackground(Theme.BACKGROUND);

        JTextField nomField = new JTextField(nom, 20);
        styleTextField(nomField);
        JTextField coefField = new JTextField(formatCoef(coefActuel), 10);
        styleTextField(coefField);
        ((PlainDocument) coefField.getDocument()).setDocumentFilter(new CoefFilter());

        JLabel labelNom = new JLabel("Nom de la matière :");
        JLabel labelCoef = new JLabel("Coefficient :");
        styleLabel(labelNom);
        styleLabel(labelCoef);

        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setBackground(Theme.BACKGROUND);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        centerPanel.add(labelNom, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        centerPanel.add(nomField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE;
        centerPanel.add(labelCoef, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        centerPanel.add(coefField, gbc);

        JButton validButton = themedButton("Enregistrer");
        validButton.addActionListener(e -> {
            String nouveauNom = nomField.getText().trim();
            if (!nomMatiereValide(frame, nouveauNom)) return;
            Double coefficient = parseCoef(coefField.getText());
            if (coefficient == null || coefficient <= 0) {
                JOptionPane.showMessageDialog(frame, "Coefficient invalide (ex : 1, 2, 0.5)", "Coefficient invalide", JOptionPane.ERROR_MESSAGE);
                return;
            }
            boolean renomme = !nouveauNom.equals(nom);
            if (renomme && !nouveauNom.equalsIgnoreCase(nom) && CsvMirror.matiereExiste(nouveauNom)) {
                JOptionPane.showMessageDialog(frame, "Une matière porte déjà ce nom.", "Erreur", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Database.synchroniser();
            boolean enLigne = Database.ping();

            if (enLigne) {
                if (!MatiereDAO.modifier(nom, nouveauNom, coefficient)) {
                    JOptionPane.showMessageDialog(frame, "La base de données a refusé la modification.", "Erreur", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } else if (renomme && Config.bddConfiguree()) {
                // Le renommage hors-ligne ne pourrait pas être retrouvé dans la BDD au retour en ligne
                JOptionPane.showMessageDialog(frame, "Connexion BDD indisponible : impossible de renommer la matière hors-ligne.\nTu peux quand même changer son coefficient.", "Mode Hors-Ligne", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            CsvMirror.modifierMatiere(nom, nouveauNom, coefficient, enLigne);

            rechargerApplication();
            frame.dispose();
        });

        JButton deleteButton = Theme.boutonSecondaire("Supprimer la matière");
        deleteButton.setForeground(Theme.RETARD);
        deleteButton.addActionListener(e -> {
            int nb = CsvMirror.listerNotes(nom).size();
            int choix = JOptionPane.showConfirmDialog(frame,
                    "Supprimer « " + nom + " »" + (nb > 0 ? " et ses " + nb + " note(s)" : "") + " ?\nCette action est définitive.",
                    "Supprimer la matière", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choix != JOptionPane.YES_OPTION) return;

            Database.synchroniser();
            if (Database.ping()) {
                if (!MatiereDAO.supprimer(nom)) {
                    JOptionPane.showMessageDialog(frame, "La base de données a refusé la suppression.", "Erreur", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } else if (Config.bddConfiguree()) {
                JOptionPane.showMessageDialog(frame, "Connexion BDD indisponible : impossible de supprimer une matière hors-ligne.", "Mode Hors-Ligne", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            CsvMirror.supprimerMatiere(nom);

            rechargerApplication();
            frame.dispose();
        });

        JButton annulButton = Theme.boutonSecondaire("Annuler");
        annulButton.addActionListener(e -> frame.dispose());

        JPanel btnPanel = new JPanel();
        btnPanel.setBackground(Theme.BACKGROUND);
        btnPanel.add(validButton);
        btnPanel.add(annulButton);
        btnPanel.add(deleteButton);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.BACKGROUND);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));
        panel.add(centerPanel, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);

        frame.getContentPane().add(panel);
        frame.getRootPane().setDefaultButton(validButton);
        frame.setVisible(true);
    }

    /** Double-clic sur une matière (1re colonne des notes, ou n'importe où dans les moyennes) : ses paramètres. */
    static void doubleClicMatiere(JTable table, boolean colonneMatiereSeulement) {
        // mousePressed plutôt que mouseClicked : un "clic" n'est pas signalé si la souris bouge d'un pixel pendant l'appui
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || col < 0) return;
                if (colonneMatiereSeulement && table.convertColumnIndexToModel(col) != 0) return;
                Object nom = table.getModel().getValueAt(table.convertRowIndexToModel(row), 0);
                if (nom != null) ModifierMatiere(nom.toString());
            }
        });
    }

    static void AddNote() {
        ListMatiere();
        if (nomsMatieres.length == 0) {
            JOptionPane.showMessageDialog(fenetre, "Ajoute d'abord une matière (bouton « Ajouter Une Matière »).", "Aucune matière", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFrame addNoteFrame = new JFrame("Nouvelle Note");
        addNoteFrame.setSize(600, 340);
        addNoteFrame.setLocationRelativeTo(null);
        addNoteFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addNoteFrame.getContentPane().setBackground(Theme.BACKGROUND);

        ListMatiere();
        JComboBox<String> comboMatiere = new JComboBox<>(nomsMatieres);
        comboMatiere.setFont(Theme.FONT_LABEL);

        JTextField noteField = new JTextField(10);
        styleTextField(noteField);
        ((PlainDocument) noteField.getDocument()).setDocumentFilter(new NoteFilter());

        JLabel labelMatiere = new JLabel("Matière :");
        JLabel labelNote = new JLabel("Note :");
        styleLabel(labelMatiere);
        styleLabel(labelNote);

        JTextField coefField = new JTextField("1", 10);
        styleTextField(coefField);
        ((PlainDocument) coefField.getDocument()).setDocumentFilter(new CoefFilter());
        JLabel labelCoef = new JLabel("Coefficient :");
        styleLabel(labelCoef);

        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setBackground(Theme.BACKGROUND);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        centerPanel.add(labelMatiere, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        centerPanel.add(comboMatiere, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE;
        centerPanel.add(labelNote, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        centerPanel.add(noteField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE;
        centerPanel.add(labelCoef, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        centerPanel.add(coefField, gbc);

        JButton validButton = themedButton("Valider");
        validButton.addActionListener(e -> {
            String saisie = noteField.getText();
            if (!saisie.matches("\\d+/\\d+")) {
                JOptionPane.showMessageDialog(addNoteFrame, "Format attendu : note/quotient (ex : 15/20)", "Format invalide", JOptionPane.ERROR_MESSAGE);
                return;
            }

            String[] parts = saisie.split("/");
            int note = Integer.parseInt(parts[0]);
            int quotient = Integer.parseInt(parts[1]);

            Double coefficient = parseCoef(coefField.getText());
            if (coefficient == null || coefficient <= 0) {
                JOptionPane.showMessageDialog(addNoteFrame, "Coefficient invalide (ex : 1, 2, 0.5)", "Coefficient invalide", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (note > quotient) {
                JOptionPane.showMessageDialog(addNoteFrame, "La note ne peut pas être supérieure au quotient", "Note invalide", JOptionPane.ERROR_MESSAGE);
                return;
            }

            String matiereChoisie = (String) comboMatiere.getSelectedItem();

            // Remet d'abord BDD et CSV d'accord si la BDD vient de revenir
            Database.synchroniser();

            Integer matiereId = MatiereDAO.trouverIdParNom(matiereChoisie);

            // tentative d'insertion BDD
            boolean insertionOk = false;
            if (matiereId != null) {
                insertionOk = NoteDAO.inserer(matiereId, note, quotient, coefficient);
            }

            // Le CSV local est mis à jour en même temps que la BDD
            // (statut SYNC si la BDD a accepté la note, ADD si elle reste à envoyer)
            CsvMirror.ajouterNote(matiereChoisie, note, quotient, coefficient, insertionOk);

            if (!insertionOk && Config.bddConfiguree()) {
                JOptionPane.showMessageDialog(addNoteFrame, "Connexion BDD indisponible. La note a été sauvegardée dans le fichier local (CSV).\nElle sera envoyée à la BDD dès la reconnexion.", "Mode Hors-Ligne", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(addNoteFrame, "Note enregistrée avec succès !");
            }

            rechargerApplication();
            addNoteFrame.dispose();
        });

        JButton annulButton = Theme.boutonSecondaire("Annuler");
        annulButton.addActionListener(e -> addNoteFrame.dispose());

        JPanel btnValidAnnulPanel = new JPanel();
        btnValidAnnulPanel.setBackground(Theme.BACKGROUND);
        btnValidAnnulPanel.add(validButton);
        btnValidAnnulPanel.add(annulButton);

        JPanel newNotePanel = new JPanel(new BorderLayout());
        newNotePanel.setBackground(Theme.BACKGROUND);
        newNotePanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        newNotePanel.add(centerPanel, BorderLayout.CENTER);
        newNotePanel.add(btnValidAnnulPanel, BorderLayout.SOUTH);

        addNoteFrame.getContentPane().add(newNotePanel);
        addNoteFrame.setVisible(true);
    }

    /** Format affiché dans le tableau : "15/20 (coef 2)" (le coef est optionnel, 1 par défaut). */
    static final Pattern NOTE_AFFICHEE = Pattern.compile("(\\d+)/(\\d+)(?: \\(coef ([\\d.]+)\\))?");

    /** Moyenne pondérée par matière, sur 20 : somme((note/quotient) x coef) / somme(coef) x 20. */
    static List<Double> calculMoyenneGenerale() {
        DefaultTableModel model = (DefaultTableModel) tableNote.getModel();
        List<Double> moyennesParMatiere = new ArrayList<>();

        for (int row = 0; row < model.getRowCount(); row++) {
            double sommePonderee = 0;
            double sommeCoefs = 0;

            for (int col = 1; col < model.getColumnCount(); col++) {
                Object valeur = model.getValueAt(row, col);
                String texte = (valeur == null) ? "" : valeur.toString();

                Matcher m = NOTE_AFFICHEE.matcher(texte);
                if (m.matches()) {
                    double quotient = Double.parseDouble(m.group(2));
                    double coef = (m.group(3) == null) ? 1.0 : Double.parseDouble(m.group(3));
                    if (quotient > 0) {
                        sommePonderee += (Double.parseDouble(m.group(1)) / quotient) * coef;
                        sommeCoefs += coef;
                    }
                }
            }

            if (sommeCoefs > 0) {
                moyennesParMatiere.add((sommePonderee / sommeCoefs) * 20);
            } else {
                moyennesParMatiere.add(null);
            }
        }
        return moyennesParMatiere;
    }

    static void styleTextField(JTextField field) {
        Theme.styleChamp(field);
    }

    static void styleLabel(JLabel label) {
        Theme.styleLabel(label);
    }

    static Object[][] donneesInitiales;
    static String[] colonnesInitiales;

    /**
     * Charge les données à afficher depuis le CSV local.
     * Juste avant, si la BDD est joignable, BDD et CSV sont comparés puis alignés
     * (le CSV reflète donc la BDD en ligne, et reste le témoin hors-ligne).
     */
    static void chargerDonneesInitiales() {
        Database.synchroniser();

        List<String> noms = CsvMirror.listerMatieres();
        List<List<String>> notesParMatiere = new ArrayList<>();
        int maxNotes = 0;

        for (String nom : noms) {
            List<String> notesMatiere = CsvMirror.listerNotes(nom);
            notesParMatiere.add(notesMatiere);
            maxNotes = Math.max(maxNotes, notesMatiere.size());
        }
        if (maxNotes == 0) maxNotes = 1;

        colonnesInitiales = new String[maxNotes + 1];
        colonnesInitiales[0] = "Matière";
        for (int i = 1; i <= maxNotes; i++) colonnesInitiales[i] = "Note " + i;

        donneesInitiales = new Object[noms.size()][maxNotes + 1];
        for (int i = 0; i < noms.size(); i++) {
            donneesInitiales[i][0] = noms.get(i);
            List<String> notesMatiere = notesParMatiere.get(i);
            for (int c = 1; c <= maxNotes; c++) {
                donneesInitiales[i][c] = (c - 1 < notesMatiere.size()) ? notesMatiere.get(c - 1) : "";
            }
        }

        columnNote[0] = maxNotes + 1;
    }

    // Fenêtre ouverte (utilisée par le tableau de bord Main.java pour ne pas l'ouvrir deux fois).
    // Lancé seul, fermer la fenêtre quitte l'appli ; ouvert depuis Main, seule la fenêtre se ferme.
    static volatile JFrame fenetre;
    static int fermeture = JFrame.EXIT_ON_CLOSE;

    public static void main(String[] args) {

        // Évite de figer l'appli plusieurs secondes à chaque test si le Raspberry Pi est injoignable
        DriverManager.setLoginTimeout(3);

        // Aucune matière au premier lancement : l'utilisateur ajoute les siennes
        if (Database.connecter()) {
            Database.creerTables();
        }

        // Compare BDD/CSV (si en ligne) puis charge l'affichage
        chargerDonneesInitiales();

        Theme.installer();

        // La fenêtre est construite sur le fil d'affichage de Swing (obligatoire quand Main.java l'ouvre)
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Centrale Étudiant · Notes");
            frame.setSize(1000, 800);
            frame.setDefaultCloseOperation(fermeture);
            fenetre = frame;
            frame.getContentPane().setBackground(Theme.BACKGROUND);

            JPanel entete = Theme.entete("Notes et moyennes",
                    Theme.info("Clique sur une note pour la sélectionner · double-clique sur une matière pour la modifier"));

            DefaultTableModel tableUnClickable = new DefaultTableModel(donneesInitiales, colonnesInitiales) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };

            tableNote = new JTable(tableUnClickable);
            styleTable(tableNote);
            tableNote.setCellSelectionEnabled(true); // on clique sur la case d'une note
            doubleClicMatiere(tableNote, true);

            JScrollPane scroll = new JScrollPane(tableNote);

            labelGenerale = new JLabel(" ");
            labelGenerale.setFont(Theme.FONT_SECTION);
            labelGenerale.setBorder(new EmptyBorder(0, 4, 8, 0));

            tableMoyenne = new JTable(modeleMoyennes());
            styleTable(tableMoyenne);
            doubleClicMatiere(tableMoyenne, false);

            JScrollPane scrollMoyenne = new JScrollPane(tableMoyenne);

            JPanel blocMoyennes = new JPanel(new BorderLayout());
            blocMoyennes.setBackground(Theme.BACKGROUND);
            blocMoyennes.add(labelGenerale, BorderLayout.NORTH);
            blocMoyennes.add(Theme.encadrer(scrollMoyenne), BorderLayout.CENTER);

            JPanel tablesPanel = new JPanel(new GridLayout(2, 1, 0, 16));
            tablesPanel.setBackground(Theme.BACKGROUND);
            tablesPanel.setBorder(new EmptyBorder(0, 24, 0, 24));
            tablesPanel.add(Theme.encadrer(scroll));
            tablesPanel.add(blocMoyennes);

            JButton btnNote = themedButton("Ajouter Une Note");
            btnNote.addActionListener(e -> AddNote());

            JButton btnDeleteNote = Theme.boutonSecondaire("Supprimer la note sélectionnée");
            btnDeleteNote.addActionListener(e -> DeleteNote());

            JButton btnMatiere = Theme.boutonSecondaire("Ajouter Une Matière");
            btnMatiere.addActionListener(e -> AddMatiere());

            JPanel btnSurround = new JPanel();
            btnSurround.setBackground(Theme.BACKGROUND);
            btnSurround.setBorder(new EmptyBorder(16, 0, 16, 0));
            btnSurround.add(btnNote);
            btnSurround.add(btnDeleteNote);
            btnSurround.add(btnMatiere);

            JPanel panel = new JPanel(new BorderLayout());
            panel.setBackground(Theme.BACKGROUND);
            panel.add(entete, BorderLayout.NORTH);
            panel.add(tablesPanel, BorderLayout.CENTER);
            panel.add(btnSurround, BorderLayout.SOUTH);

            frame.getContentPane().add(panel);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    static void styleTable(JTable table) {
        Theme.styleTable(table);
    }
}