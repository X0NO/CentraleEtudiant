package com.centrale;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;


public class Devoirs {

    static JButton themedButton(String text) {
        return Theme.bouton(text);
    }

    static void styleTextField(JTextField field) {
        Theme.styleChamp(field);
    }

    static void styleLabel(JLabel label) {
        Theme.styleLabel(label);
    }

    // =====================================================================
    // CONNEXION BASE DE DONNÉES (facultative, comme dans Note.java)
    // =====================================================================
    // Base facultative : adresse et identifiants dans les Paramètres (voir Config).
    // Sans base configurée, tout reste dans le fichier CSV local.

    // =====================================================================
    // MODÈLE
    // =====================================================================
    static final class Devoir {
        final String uid;          // identifiant unique, créé dès la saisie (même hors-ligne)
        final String matiere;
        final LocalDate pourLe;
        final String description;
        boolean fait;
        String statut;

        Devoir(String uid, String matiere, LocalDate pourLe, String description, boolean fait, String statut) {
            this.uid = uid;
            this.matiere = matiere;
            this.pourLe = pourLe;
            this.description = description;
            this.fait = fait;
            this.statut = statut;
        }

        Devoir copie() {
            return new Devoir(uid, matiere, pourLe, description, fait, statut);
        }

        String toLine() {
            return uid + ";" + matiere + ";" + pourLe + ";" + (fait ? "1" : "0") + ";" + statut + ";"
                    + DevoirCsv.coder(description);
        }
    }

    /** Du plus urgent au moins urgent (même jour : ordre alphabétique des matières). */
    static final Comparator<Devoir> PAR_ECHEANCE =
            Comparator.comparing((Devoir d) -> d.pourLe).thenComparing(d -> d.matiere.toLowerCase());

    static final DateTimeFormatter SAISIE = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    // =====================================================================
    // MIROIR CSV LOCAL (2e TÉMOIN DES DEVOIRS)
    //
    // Fichier devoirs_backup.csv, une ligne par devoir :
    //     uid;matiere;date;fait;statut;description
    //   uid         : identifiant unique du devoir (le même dans la BDD)
    //   date        : AAAA-MM-JJ
    //   fait        : 0 ou 1
    //   statut      : SYNC = présent dans la BDD ET dans le fichier
    //                 ADD  = ajouté hors-ligne, pas encore envoyé à la BDD
    //                 MOD  = coché/décoché hors-ligne, pas encore modifié dans la BDD
    //                 DEL  = supprimé hors-ligne, pas encore supprimé de la BDD
    //   description : en dernier (peut contenir des ';'), retours à la ligne codés "\n"
    //
    // Règles (identiques à Note.java) :
    //  - chaque ajout/modification/suppression met à jour la BDD ET le CSV en même temps ;
    //  - BDD injoignable : le CSV sert de témoin (affichage + modifications) ;
    //  - retour en ligne : s'il n'y a rien d'en attente dans le CSV, il est
    //    réécrit à partir de la BDD ; sinon les changements en attente sont
    //    appliqués à la BDD, puis le CSV est réaligné sur la BDD.
    // =====================================================================
    static final class DevoirCsv {
        static final Path FILE = Config.fichier("devoirs_backup.csv");
        static final String HEADER = "uid;matiere;date;fait;statut;description";
        static final String SYNC = "SYNC";
        static final String ADD = "ADD";
        static final String MOD = "MOD";
        static final String DEL = "DEL";

        /** Lit le CSV. Si la lecture échoue, on lève une exception plutôt que d'écraser le fichier ensuite. */
        static synchronized List<Devoir> lire() {
            List<Devoir> res = new ArrayList<>();
            Path p = FILE;
            if (!Files.exists(p)) return res;
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.equals(HEADER)) continue;
                    String[] parts = line.split(";", 6);
                    if (parts.length != 6) continue;
                    try {
                        res.add(new Devoir(parts[0], parts[1], LocalDate.parse(parts[2]),
                                decoder(parts[5]), parts[3].equals("1"), parts[4]));
                    } catch (DateTimeParseException ignored) {
                        // ligne illisible : ignorée
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Lecture de " + FILE + " impossible", e);
            }
            return res;
        }

        /** Écriture atomique (fichier temporaire puis remplacement) pour ne jamais laisser un CSV à moitié écrit. */
        static synchronized void ecrire(List<Devoir> entries) {
            List<String> lignes = new ArrayList<>();
            lignes.add(HEADER);
            for (Devoir d : entries) lignes.add(d.toLine());
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

        // ---------- Mises à jour appelées en même temps que la BDD ----------

        static synchronized void ajouter(Devoir d, boolean dbOk) {
            List<Devoir> l = lire();
            d.statut = dbOk ? SYNC : ADD;
            l.add(d);
            ecrire(l);
        }

        /** Hors-ligne, un devoir déjà synchronisé passe en MOD ; un devoir ADD reste ADD (il sera envoyé tel quel). */
        static synchronized void changerFait(String uid, boolean fait, boolean dbOk) {
            List<Devoir> l = lire();
            for (Devoir e : l) {
                if (e.uid.equals(uid) && !e.statut.equals(DEL)) {
                    e.fait = fait;
                    if (!e.statut.equals(ADD)) e.statut = dbOk ? SYNC : MOD;
                    ecrire(l);
                    return;
                }
            }
        }

        /**
         * Hors-ligne, un devoir déjà synchronisé est marqué DEL (à supprimer de la BDD plus tard),
         * un devoir ajouté hors-ligne (ADD) est simplement retiré du fichier.
         * @return true si le devoir existait dans le fichier
         */
        static synchronized boolean supprimer(String uid, boolean dbOk) {
            List<Devoir> l = lire();
            for (int i = 0; i < l.size(); i++) {
                Devoir e = l.get(i);
                if (e.uid.equals(uid) && !e.statut.equals(DEL)) {
                    if (dbOk || e.statut.equals(ADD)) l.remove(i);
                    else e.statut = DEL;
                    ecrire(l);
                    return true;
                }
            }
            return false;
        }

        // ---------- Lecture (utilisée pour l'affichage) ----------

        static synchronized List<Devoir> listerVisibles() {
            List<Devoir> res = new ArrayList<>();
            for (Devoir e : lire()) {
                if (!e.statut.equals(DEL)) res.add(e);
            }
            return res;
        }

        // ---------- Synchronisation (appelée en arrière-plan, voir Database.synchroniser) ----------

        static synchronized List<Devoir> copiesEnAttente() {
            List<Devoir> res = new ArrayList<>();
            for (Devoir d : lire()) if (!d.statut.equals(SYNC)) res.add(d.copie());
            return res;
        }

        /**
         * Le fichier devient le contenu de la BDD, plus les changements locaux pas encore envoyés
         * (échec d'envoi, ou faits pendant la synchronisation).
         * @param envoyes uid -> ligne envoyée avec succès
         */
        static synchronized void fusionner(List<Devoir> distants, Map<String, String> envoyes) {
            Map<String, Devoir> res = new LinkedHashMap<>();
            for (Devoir d : distants) res.put(d.uid, d);
            for (Devoir l : lire()) {
                String envoye = envoyes.get(l.uid);
                boolean changePendant = envoye != null && !envoye.equals(l.toLine());
                if (l.statut.equals(SYNC) && !changePendant) continue;
                if (envoye != null && !changePendant) continue;
                if (l.statut.equals(SYNC)) l.statut = MOD;
                res.put(l.uid, l);
            }
            ecrire(new ArrayList<>(res.values()));
        }
    }

    // =====================================================================
    // ACCÈS BASE DE DONNÉES (une connexion par synchronisation, toujours hors de l'interface)
    // =====================================================================
    static final class Database {

        /** true une fois la table vérifiée/créée. */
        static volatile boolean schemaPret = false;

        static void creerTables(Connection c) throws SQLException {
            try (Statement stmt = c.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS devoirs (" +
                        "id SERIAL PRIMARY KEY," +
                        "uid VARCHAR(36) NOT NULL UNIQUE," +
                        "matiere VARCHAR(100) NOT NULL," +
                        "date_rendu DATE NOT NULL," +
                        "fait BOOLEAN NOT NULL DEFAULT FALSE," +
                        "description TEXT NOT NULL," +
                        "date_creation TIMESTAMP DEFAULT NOW()" +
                        ")");
            }
            schemaPret = true;
        }

        /**
         * Envoie les changements locaux (ajouts, cases cochées, suppressions), puis aligne le CSV sur la BDD.
         * Immédiat si la BDD n'est pas configurée ou vient d'échouer. À lancer via Bdd.planifier(...).
         */
        static synchronized void synchroniser() {
            if (!Bdd.disponible()) return;
            try (Connection c = Bdd.connexion()) {
                if (!schemaPret) creerTables(c);
                Map<String, String> envoyes = new HashMap<>();
                for (Devoir d : DevoirCsv.copiesEnAttente()) {
                    try {
                        if (d.statut.equals(DevoirCsv.DEL)) DevoirDAO.supprimer(c, d.uid);
                        else DevoirDAO.enregistrer(c, d);
                        envoyes.put(d.uid, d.toLine());
                    } catch (SQLException e) {
                        System.err.println("Envoi du devoir " + d.uid + " impossible : " + e.getMessage());
                    }
                }
                DevoirCsv.fusionner(DevoirDAO.lireTout(c), envoyes);
            } catch (SQLException e) {
                System.err.println("BDD injoignable, devoirs gardés sur cet ordinateur : " + e.getMessage());
            }
        }
    }

    // =====================================================================
    // DAO DEVOIRS
    // =====================================================================
    static final class DevoirDAO {

        /** Ajoute le devoir, ou le met à jour s'il existe déjà (même uid). */
        static void enregistrer(Connection c, Devoir d) throws SQLException {
            String sql = "INSERT INTO devoirs (uid, matiere, date_rendu, fait, description) VALUES (?, ?, ?, ?, ?) "
                       + "ON CONFLICT (uid) DO UPDATE SET matiere = EXCLUDED.matiere, date_rendu = EXCLUDED.date_rendu, "
                       + "fait = EXCLUDED.fait, description = EXCLUDED.description";
            try (PreparedStatement stmt = c.prepareStatement(sql)) {
                stmt.setString(1, d.uid);
                stmt.setString(2, d.matiere);
                stmt.setObject(3, d.pourLe);
                stmt.setBoolean(4, d.fait);
                stmt.setString(5, d.description);
                stmt.executeUpdate();
            }
        }

        static void supprimer(Connection c, String uid) throws SQLException {
            try (PreparedStatement stmt = c.prepareStatement("DELETE FROM devoirs WHERE uid = ?")) {
                stmt.setString(1, uid);
                stmt.executeUpdate();
            }
        }

        static List<Devoir> lireTout(Connection c) throws SQLException {
            List<Devoir> l = new ArrayList<>();
            try (PreparedStatement stmt = c.prepareStatement(
                    "SELECT uid, matiere, date_rendu, fait, description FROM devoirs ORDER BY date_rendu, id");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    l.add(new Devoir(rs.getString("uid"), rs.getString("matiere"),
                            rs.getObject("date_rendu", LocalDate.class), rs.getString("description"),
                            rs.getBoolean("fait"), DevoirCsv.SYNC));
                }
            }
            return l;
        }
    }

    static List<Devoir> devoirs = new ArrayList<>();
    static boolean afficherFaits = false;
    static ListePanel liste;

    // =====================================================================
    // COULEURS PAR MATIÈRE (même principe que Calendar.java)
    // =====================================================================
    private static final Map<String, Color> couleurs = new HashMap<>();

    static void attribuerCouleurs() {
        List<String> matieres = devoirs.stream()
                .map(d -> d.matiere)
                .distinct()
                .sorted()
                .toList();

        couleurs.clear();
        for (int i = 0; i < matieres.size(); i++) {
            float teinte = (float) i / matieres.size();
            couleurs.put(matieres.get(i), Theme.teinte(teinte));
        }
    }

    // =====================================================================
    // LISTE FAÇON PRONOTE : devoirs groupés par date, le plus proche en haut
    // =====================================================================

    /** Panneau qui suit la largeur du scroll (les descriptions passent à la ligne au lieu de déborder). */
    static class ListePanel extends JPanel implements Scrollable {
        ListePanel() {
            super(new GridBagLayout());
            setBackground(Theme.BACKGROUND);
            setBorder(new EmptyBorder(0, 24, 20, 24));
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 32; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Recharge l'affichage depuis le CSV local (déjà aligné sur la BDD si elle est joignable). */
    static void rafraichirListe() {
        devoirs = DevoirCsv.listerVisibles();
        if (liste == null) return; // fenêtre des devoirs pas ouverte (ajout depuis le tableau de bord)
        attribuerCouleurs();
        liste.removeAll();

        LocalDate aujourdhui = LocalDate.now();
        List<Devoir> tries = new ArrayList<>(devoirs);
        tries.sort(PAR_ECHEANCE);

        // Regroupement : "En retard" (non faits, date passée) puis une section par jour à venir
        Map<String, List<Devoir>> sections = new LinkedHashMap<>();
        for (Devoir d : tries) {
            boolean passe = d.pourLe.isBefore(aujourdhui);
            if (passe && d.fait) continue;               // devoir passé et fait : plus rien à voir
            if (d.fait && !afficherFaits) continue;
            String titre = passe ? "En retard" : libelleJour(d.pourLe, aujourdhui);
            sections.computeIfAbsent(titre, k -> new ArrayList<>()).add(d);
        }

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;

        if (sections.isEmpty()) {
            JLabel vide = new JLabel("Aucun devoir à faire", SwingConstants.CENTER);
            vide.setFont(Theme.FONT_LABEL);
            vide.setForeground(Theme.TEXT_DISCRET);
            vide.setBorder(new EmptyBorder(40, 0, 0, 0));
            liste.add(vide, gbc);
            gbc.gridy++;
        }

        for (Map.Entry<String, List<Devoir>> s : sections.entrySet()) {
            boolean retard = s.getKey().equals("En retard");
            long restants = s.getValue().stream().filter(d -> !d.fait).count();

            JLabel entete = new JLabel(s.getKey() + "   ·   " + restants + " à faire");
            entete.setFont(Theme.FONT_SECTION);
            entete.setForeground(retard ? Theme.RETARD : Theme.ACCENT);
            entete.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                    new EmptyBorder(18, 2, 6, 2)));
            gbc.insets = new Insets(0, 0, 8, 0);
            liste.add(entete, gbc);
            gbc.gridy++;

            for (Devoir d : s.getValue()) {
                gbc.insets = new Insets(0, 0, 8, 0);
                liste.add(carte(d, retard, aujourdhui), gbc);
                gbc.gridy++;
            }
        }

        // pousse tout vers le haut
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        JPanel remplissage = new JPanel();
        remplissage.setOpaque(false);
        liste.add(remplissage, gbc);

        liste.revalidate();
        liste.repaint();
    }

    /** "Pour aujourd'hui", "Pour demain", "Pour lundi 5 octobre"... */
    static String libelleJour(LocalDate jour, LocalDate aujourdhui) {
        long ecart = ChronoUnit.DAYS.between(aujourdhui, jour);
        if (ecart == 0) return "Pour aujourd'hui";
        if (ecart == 1) return "Pour demain";
        String motif = (jour.getYear() == aujourdhui.getYear()) ? "EEEE d MMMM" : "EEEE d MMMM yyyy";
        return "Pour " + jour.format(DateTimeFormatter.ofPattern(motif, Locale.FRENCH));
    }

    /** Une carte de devoir : bande de couleur de la matière, matière, consigne, case "Fait", suppression. */
    static JPanel carte(Devoir d, boolean retard, LocalDate aujourdhui) {
        Color couleur = couleurs.getOrDefault(d.matiere, Theme.ACCENT);
        Color texte = d.fait ? Theme.TEXT_DISCRET : Theme.TEXT_PRIMARY;

        Theme.PanneauArrondi carte = new Theme.PanneauArrondi(new BorderLayout(12, 0), 14);
        carte.setBackground(d.fait ? Theme.SURFACE_ALT : Theme.SURFACE);
        carte.setBande(d.fait ? Theme.BORDER : couleur, 5);
        carte.setBorder(new EmptyBorder(12, 18, 12, 12));

        JLabel matiere = new JLabel(d.matiere);
        matiere.setFont(Theme.FONT_BUTTON.deriveFont(14f));
        matiere.setForeground(d.fait ? Theme.TEXT_DISCRET : couleur);

        JPanel ligneHaut = new JPanel(new BorderLayout());
        ligneHaut.setOpaque(false);
        ligneHaut.add(matiere, BorderLayout.WEST);
        if (retard) {
            long jours = ChronoUnit.DAYS.between(d.pourLe, aujourdhui);
            JLabel echeance = new JLabel("Pour le " + d.pourLe.format(SAISIE)
                    + " (" + jours + " jour" + (jours > 1 ? "s" : "") + " de retard)");
            echeance.setFont(Theme.FONT_TABLE);
            echeance.setForeground(Theme.RETARD);
            ligneHaut.add(echeance, BorderLayout.EAST);
        }

        JTextArea consigne = new JTextArea(d.description.isBlank() ? "(pas de consigne)" : d.description);
        consigne.setFont(Theme.FONT_TABLE);
        consigne.setForeground(texte);
        consigne.setOpaque(false);
        consigne.setEditable(false);
        consigne.setFocusable(false);
        consigne.setLineWrap(true);
        consigne.setWrapStyleWord(true);
        consigne.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel contenu = new JPanel(new BorderLayout());
        contenu.setOpaque(false);
        contenu.add(ligneHaut, BorderLayout.NORTH);
        contenu.add(consigne, BorderLayout.CENTER);

        JCheckBox fait = new JCheckBox("Fait", d.fait);
        fait.setFont(Theme.FONT_TABLE);
        fait.setForeground(texte);
        fait.setOpaque(false);
        fait.setFocusPainted(false);
        fait.setCursor(new Cursor(Cursor.HAND_CURSOR));
        fait.addActionListener(e -> {
            // CSV tout de suite (affichage instantané), BDD ensuite en arrière-plan
            DevoirCsv.changerFait(d.uid, fait.isSelected(), false);
            SwingUtilities.invokeLater(Devoirs::apresChangement);
        });

        JButton supprimer = new JButton("×");
        supprimer.setFont(Theme.FONT_BUTTON);
        supprimer.setForeground(Theme.TEXT_DISCRET);
        supprimer.setContentAreaFilled(false);
        supprimer.setBorder(new EmptyBorder(2, 8, 2, 4));
        supprimer.setFocusPainted(false);
        supprimer.setCursor(new Cursor(Cursor.HAND_CURSOR));
        supprimer.setToolTipText("Supprimer ce devoir");
        supprimer.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) { supprimer.setForeground(Theme.RETARD); }
            @Override
            public void mouseExited(MouseEvent e) { supprimer.setForeground(Theme.TEXT_DISCRET); }
        });
        supprimer.addActionListener(e -> {
            int choix = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(liste),
                    "Supprimer le devoir de " + d.matiere + " ?", "Supprimer", JOptionPane.YES_NO_OPTION);
            if (choix != JOptionPane.YES_OPTION) return;

            DevoirCsv.supprimer(d.uid, false);
            apresChangement();
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actions.add(fait);
        actions.add(supprimer);

        carte.add(contenu, BorderLayout.CENTER);
        carte.add(actions, BorderLayout.EAST);
        return carte;
    }

    // =====================================================================
    // FENÊTRE AJOUT D'UN DEVOIR
    // =====================================================================

    /** Date : chiffres et '/' uniquement, format JJ/MM/AAAA. */
    static class DateFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
            replace(fb, offset, 0, string, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            String currentText = fb.getDocument().getText(0, fb.getDocument().getLength());
            String proposedText = currentText.substring(0, offset) + text + currentText.substring(offset + length);

            if (proposedText.matches("\\d{0,2}(/\\d{0,2}(/\\d{0,4})?)?")) {
                super.replace(fb, offset, length, text, attrs);
            }
        }
    }

    /** Matières proposées : celles déjà utilisées dans les devoirs, puis celles du programme du BUT. */
    static List<String> matieresConnues() {
        List<String> noms = new ArrayList<>();
        for (Devoir d : devoirs) {
            if (!noms.contains(d.matiere)) noms.add(d.matiere);
        }
        for (Programme.Matiere m : Programme.TOUTES) {
            String nom = m.code() + " " + m.nom();
            if (!noms.contains(nom)) noms.add(nom);
        }
        return noms;
    }

    static void AddDevoir() {
        JFrame addDevoirFrame = new JFrame("Nouveau Devoir");
        addDevoirFrame.setSize(600, 420);
        addDevoirFrame.setLocationRelativeTo(null);
        addDevoirFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addDevoirFrame.getContentPane().setBackground(Theme.BACKGROUND);

        JComboBox<String> comboMatiere = new JComboBox<>(matieresConnues().toArray(new String[0]));
        comboMatiere.setEditable(true); // on peut aussi taper une matière qui n'est pas dans la liste
        comboMatiere.setFont(Theme.FONT_LABEL);

        JTextField dateField = new JTextField(LocalDate.now().plusDays(1).format(SAISIE), 10);
        styleTextField(dateField);
        ((PlainDocument) dateField.getDocument()).setDocumentFilter(new DateFilter());

        JTextArea consigneArea = new JTextArea(5, 20);
        consigneArea.setFont(Theme.FONT_LABEL);
        consigneArea.setBackground(Theme.SURFACE);
        consigneArea.setForeground(Theme.TEXT_PRIMARY);
        consigneArea.setCaretColor(Theme.TEXT_PRIMARY);
        consigneArea.setLineWrap(true);
        consigneArea.setWrapStyleWord(true);
        consigneArea.setBorder(new EmptyBorder(4, 6, 4, 6));
        JScrollPane consigneScroll = new JScrollPane(consigneArea);
        consigneScroll.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 10");

        JLabel labelMatiere = new JLabel("Matière :");
        JLabel labelDate = new JLabel("Pour le (JJ/MM/AAAA) :");
        JLabel labelConsigne = new JLabel("Travail à faire :");
        styleLabel(labelMatiere);
        styleLabel(labelDate);
        styleLabel(labelConsigne);

        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setBackground(Theme.BACKGROUND);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        centerPanel.add(labelMatiere, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        centerPanel.add(comboMatiere, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        centerPanel.add(labelDate, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        centerPanel.add(dateField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        centerPanel.add(labelConsigne, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1; gbc.weighty = 1;
        centerPanel.add(consigneScroll, gbc);

        JButton validButton = themedButton("Valider");
        validButton.addActionListener(e -> {
            Object choix = comboMatiere.getSelectedItem();
            String matiere = (choix == null) ? "" : choix.toString().trim();
            if (matiere.isEmpty()) {
                JOptionPane.showMessageDialog(addDevoirFrame, "Choisis ou tape une matière.", "Erreur", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (matiere.contains(";")) {
                JOptionPane.showMessageDialog(addDevoirFrame, "Le caractère ';' est interdit dans le nom (séparateur du CSV).", "Erreur", JOptionPane.ERROR_MESSAGE);
                return;
            }

            LocalDate pourLe;
            try {
                pourLe = LocalDate.parse(dateField.getText().trim(), SAISIE);
            } catch (DateTimeParseException ex) {
                JOptionPane.showMessageDialog(addDevoirFrame, "Date invalide. Format attendu : JJ/MM/AAAA (ex : 06/10/2026)", "Date invalide", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (pourLe.isBefore(LocalDate.now())) {
                int rep = JOptionPane.showConfirmDialog(addDevoirFrame, "Cette date est déjà passée. Ajouter quand même ?", "Date passée", JOptionPane.YES_NO_OPTION);
                if (rep != JOptionPane.YES_OPTION) return;
            }

            String consigne = consigneArea.getText().trim();
            if (consigne.isEmpty()) {
                JOptionPane.showMessageDialog(addDevoirFrame, "Décris le travail à faire.", "Erreur", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Devoir nouveau = new Devoir(UUID.randomUUID().toString(), matiere, pourLe, consigne, false, DevoirCsv.ADD);

            // CSV tout de suite (statut ADD), envoyé à la BDD en arrière-plan
            DevoirCsv.ajouter(nouveau, false);
            addDevoirFrame.dispose();
            apresChangement();
        });

        JButton annulButton = Theme.boutonSecondaire("Annuler");
        annulButton.addActionListener(e -> addDevoirFrame.dispose());

        JPanel btnValidAnnulPanel = new JPanel();
        btnValidAnnulPanel.setBackground(Theme.BACKGROUND);
        btnValidAnnulPanel.add(validButton);
        btnValidAnnulPanel.add(annulButton);

        JPanel newDevoirPanel = new JPanel(new BorderLayout());
        newDevoirPanel.setBackground(Theme.BACKGROUND);
        newDevoirPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        newDevoirPanel.add(centerPanel, BorderLayout.CENTER);
        newDevoirPanel.add(btnValidAnnulPanel, BorderLayout.SOUTH);

        addDevoirFrame.getContentPane().add(newDevoirPanel);
        addDevoirFrame.setVisible(true);
    }

    // Fenêtre ouverte (utilisée par le tableau de bord Main.java pour ne pas l'ouvrir deux fois).
    // Lancé seul, fermer la fenêtre quitte l'appli ; ouvert depuis Main, seule la fenêtre se ferme.
    static volatile JFrame fenetre;
    static int fermeture = JFrame.EXIT_ON_CLOSE;

    public static void main(String[] args) {

        Theme.installer();

        // L'affichage vient du CSV : la fenêtre s'ouvre tout de suite, la BDD est synchronisée ensuite
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Centrale Étudiant · Devoirs");
            frame.setSize(1000, 800);
            frame.setDefaultCloseOperation(fermeture);
            fenetre = frame;
            frame.getContentPane().setBackground(Theme.BACKGROUND);

            JPanel haut = Theme.entete("Travail à faire", Theme.info("Du plus urgent au moins urgent"));

            liste = new ListePanel();
            rafraichirListe();

            JScrollPane scroll = new JScrollPane(liste);
            scroll.setOpaque(true);
            scroll.setBackground(Theme.BACKGROUND);
            scroll.getViewport().setBackground(Theme.BACKGROUND);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);

            JButton btnDevoir = themedButton("Ajouter Un Devoir");
            btnDevoir.addActionListener(e -> AddDevoir());

            JButton btnFaits = Theme.boutonSecondaire("Afficher les devoirs faits");
            btnFaits.addActionListener(e -> {
                afficherFaits = !afficherFaits;
                btnFaits.setText(afficherFaits ? "Masquer les devoirs faits" : "Afficher les devoirs faits");
                rafraichirListe();
            });

            JPanel btnSurround = new JPanel();
            btnSurround.setBackground(Theme.BACKGROUND);
            btnSurround.setBorder(new EmptyBorder(16, 0, 16, 0));
            btnSurround.add(btnDevoir);
            btnSurround.add(btnFaits);

            JPanel panel = new JPanel(new BorderLayout());
            panel.setBackground(Theme.BACKGROUND);
            panel.add(haut, BorderLayout.NORTH);
            panel.add(scroll, BorderLayout.CENTER);
            panel.add(btnSurround, BorderLayout.SOUTH);

            frame.getContentPane().add(panel);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            Bdd.planifier(Database::synchroniser, Devoirs::rafraichirListe);
        });
    }

    /** Après chaque modification : affichage tout de suite, BDD ensuite en arrière-plan. */
    static void apresChangement() {
        rafraichirListe();
        Main.dernierRafraichissementLocal = 0; // le tableau de bord relira les devoirs
        Bdd.planifier(Database::synchroniser, Devoirs::rafraichirListe);
    }
}
