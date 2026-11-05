package com.centrale;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.table.JTableHeader;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;

/**
 * Apparence commune à toutes les apps de Centrale Étudiant (Note, Calendar, Devoirs, Mails, Main).
 *
 * - Deux modes : sombre et clair. Le choix est mémorisé et partagé par toutes les apps.
 * - Les couleurs ci-dessous changent toutes seules quand on bascule de mode :
 *   un composant coloré avec Theme.SURFACE (par exemple) n'a pas besoin d'être recréé.
 * - L'apparence des composants Swing (boutons, champs, menus, barres de défilement...) vient de FlatLaf.
 */
public final class Theme {

    private Theme() {}

    public enum Mode { SOMBRE, CLAIR }

    private static final Preferences PREFS = Preferences.userNodeForPackage(Theme.class);
    private static volatile Mode mode = lireMode();
    private static boolean installe = false;
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();

    static {
        FlatInterFont.install(); // police Inter (fournie avec l'appli : même rendu sur tous les ordinateurs)
    }

    // =====================================================================
    // COULEURS (valeur en mode sombre, valeur en mode clair)
    // =====================================================================

    /** Couleur qui suit le mode : sa valeur est lue au moment où elle est dessinée. */
    static final class Couleur extends Color {
        private final int sombre;
        private final int clair;

        Couleur(int sombre, int clair) {
            super(sombre);
            this.sombre = 0xFF000000 | sombre;
            this.clair = 0xFF000000 | clair;
        }

        @Override
        public int getRGB() {
            return (mode == Mode.SOMBRE) ? sombre : clair;
        }
    }

    // Palette neutre et froide, accent indigo (sombre : inspirée de Linear / Raycast ; clair : blanc cassé)
    static final Color BACKGROUND      = new Couleur(0x0F1117, 0xF5F6FA);
    static final Color SURFACE         = new Couleur(0x171A23, 0xFFFFFF);
    static final Color SURFACE_ALT     = new Couleur(0x1D2130, 0xF3F4F8);
    static final Color SURFACE_HOVER   = new Couleur(0x262B3D, 0xE9EBF2);
    static final Color BORDER          = new Couleur(0x272B38, 0xE3E5ED);
    static final Color GRILLE_LEGERE   = new Couleur(0x1E2230, 0xEEF0F5);
    static final Color TEXT_PRIMARY    = new Couleur(0xE8EAF0, 0x171923);
    static final Color TEXT_DISCRET    = new Couleur(0x8B91A3, 0x6B7085);
    static final Color ACCENT          = new Couleur(0x8B9CFF, 0x4F5BD5);
    static final Color ACCENT_HOVER    = new Couleur(0xA5B2FF, 0x3F4AC0);
    static final Color TEXT_ON_ACCENT  = new Couleur(0x0F1117, 0xFFFFFF);
    static final Color FOND_AUJOURDHUI = new Couleur(0x1B2140, 0xEDEFFF);
    static final Color SUCCES          = new Couleur(0x4ADE80, 0x16A34A);
    static final Color ATTENTION       = new Couleur(0xFBBF24, 0xD97706);
    static final Color RETARD          = new Couleur(0xF87171, 0xDC2626);
    static final Color ERREUR          = RETARD;

    /** Version transparente d'une couleur du thème (suit aussi le mode) : fond des pastilles, halos... */
    static Color transparent(Color base, int alpha) {
        return new Color(0, true) {
            @Override public int getRGB() { return (alpha << 24) | (base.getRGB() & 0xFFFFFF); }
        };
    }

    /** Couleur d'une moyenne sur 20 : vert >= 10, orange >= 8, rouge en dessous. */
    static Color couleurMoyenne(double m) {
        if (m >= 10) return SUCCES;
        if (m >= 8) return ATTENTION;
        return RETARD;
    }

    /** Couleur d'une matière (teinte 0..1) : pastel en sombre, plus soutenue en clair pour rester lisible. */
    static Color teinte(float h) {
        return new Couleur(Color.HSBtoRGB(h, 0.42f, 0.96f) & 0xFFFFFF, Color.HSBtoRGB(h, 0.62f, 0.74f) & 0xFFFFFF);
    }

    static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    // =====================================================================
    // POLICES
    // =====================================================================
    static final String POLICE = FlatInterFont.FAMILY;

    static final Font FONT_TITLE   = new Font(POLICE, Font.BOLD, 24);
    static final Font FONT_CHIFFRE = new Font(POLICE, Font.BOLD, 46);
    static final Font FONT_CHIFFRE_MOYEN = new Font(POLICE, Font.BOLD, 22);
    static final Font FONT_SECTION = new Font(POLICE, Font.BOLD, 15);
    static final Font FONT_LABEL   = new Font(POLICE, Font.PLAIN, 14);
    static final Font FONT_BUTTON  = new Font(POLICE, Font.BOLD, 13);
    static final Font FONT_TABLE   = new Font(POLICE, Font.PLAIN, 13);
    static final Font FONT_HEADER  = new Font(POLICE, Font.BOLD, 13);
    static final Font FONT_PETIT   = new Font(POLICE, Font.PLAIN, 12);
    static final Font FONT_SURTITRE = new Font(POLICE, Font.BOLD, 11);

    // =====================================================================
    // MODE SOMBRE / CLAIR
    // =====================================================================
    static Mode mode() {
        return mode;
    }

    static boolean estSombre() {
        return mode == Mode.SOMBRE;
    }

    private static Mode lireMode() {
        try {
            return Mode.valueOf(PREFS.get("mode", Mode.SOMBRE.name()));
        } catch (Exception e) {
            return Mode.SOMBRE;
        }
    }

    /** À appeler au tout début de chaque main(), avant de créer une fenêtre. Sans effet la 2e fois. */
    static synchronized void installer() {
        if (installe) return;
        installe = true;

        // barre de titre des fenêtres aux couleurs du thème (au lieu de celle du système)
        JFrame.setDefaultLookAndFeelDecorated(true);
        appliquerLookAndFeel();

        // icône de l'appli sur toutes les fenêtres (barre des tâches, Alt+Tab)
        List<Image> icones = new ArrayList<>();
        for (String nom : new String[]{"/icone-64.png", "/icone-256.png"}) {
            URL url = Theme.class.getResource(nom);
            if (url != null) icones.add(new ImageIcon(url).getImage());
        }
        if (!icones.isEmpty()) {
            Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
                if (e.getID() == WindowEvent.WINDOW_OPENED && e.getSource() instanceof Window w) w.setIconImages(icones);
            }, AWTEvent.WINDOW_EVENT_MASK);
        }
    }

    /** Passe du mode sombre au mode clair (et inversement) dans toutes les fenêtres ouvertes. */
    static void basculer() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(Theme::basculer);
            return;
        }
        mode = estSombre() ? Mode.CLAIR : Mode.SOMBRE;
        try {
            PREFS.put("mode", mode.name());
        } catch (Exception ignored) {
            // préférence non sauvegardée : le mode sera celui par défaut au prochain lancement
        }
        appliquerLookAndFeel();
        FlatLaf.updateUI();
        for (Runnable r : ecouteurs) r.run();
        for (Window w : Window.getWindows()) w.repaint();
    }

    /** Action à lancer après chaque changement de mode (ex : réafficher un texte HTML coloré). */
    static void auChangement(Runnable r) {
        ecouteurs.add(r);
    }

    private static void appliquerLookAndFeel() {
        FlatLaf.setPreferredFontFamily(POLICE);
        FlatLaf.setGlobalExtraDefaults(Map.of(
                "@accentColor", hex(ACCENT),
                "@background", hex(BACKGROUND),
                "@foreground", hex(TEXT_PRIMARY)));
        if (estSombre()) FlatDarkLaf.setup();
        else FlatLightLaf.setup();

        UIManager.put("defaultFont", new Font(POLICE, Font.PLAIN, 13));
        UIManager.put("Button.arc", 12);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("Component.focusedBorderColor", ACCENT);
        UIManager.put("Component.focusColor", transparent(ACCENT, 90));
        UIManager.put("ComboBox.buttonBackground", SURFACE_ALT);
        UIManager.put("ComboBox.background", SURFACE_ALT);
        UIManager.put("ComboBox.popupBackground", SURFACE);
        UIManager.put("ComboBox.selectionArc", 8);
        UIManager.put("List.selectionArc", 8);
        UIManager.put("TextField.background", SURFACE_ALT);
        UIManager.put("PasswordField.background", SURFACE_ALT);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("ToolTip.border", new EmptyBorder(8, 10, 8, 10));
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("ScrollPane.arc", 12);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.track", SURFACE);
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("TitlePane.background", BACKGROUND);
        UIManager.put("MenuBar.background", BACKGROUND);
        UIManager.put("PopupMenu.borderCornerRadius", 10);
        UIManager.put("MenuItem.selectionArc", 8);
        UIManager.put("MenuItem.selectionInsets", new Insets(0, 4, 0, 4));
        UIManager.put("Table.alternateRowColor", SURFACE_ALT);
        UIManager.put("Table.cellMargins", new Insets(0, 12, 0, 12));
        UIManager.put("Table.selectionBackground", ACCENT);
        UIManager.put("Table.selectionForeground", TEXT_ON_ACCENT);
        UIManager.put("Table.selectionInactiveBackground", ACCENT);
        UIManager.put("Table.selectionInactiveForeground", TEXT_ON_ACCENT);
        UIManager.put("SplitPaneDivider.gripColor", BORDER);
        UIManager.put("ToolTip.background", SURFACE_ALT);
        UIManager.put("ToolTip.foreground", TEXT_PRIMARY);
    }

    // =====================================================================
    // COMPOSANTS
    // =====================================================================

    /** Bouton principal : fond d'accent, coins arrondis. */
    static JButton bouton(String texte) {
        return bouton(texte, ACCENT, ACCENT_HOVER, TEXT_ON_ACCENT);
    }

    /** Bouton secondaire (Annuler, navigation...) : discret, même forme. */
    static JButton boutonSecondaire(String texte) {
        return bouton(texte, SURFACE_ALT, SURFACE_HOVER, TEXT_PRIMARY);
    }

    private static JButton bouton(String texte, Color fond, Color survol, Color encre) {
        JButton b = new JButton(texte);
        b.setFont(FONT_BUTTON);
        b.setBackground(fond);
        b.setForeground(encre);
        b.setFocusPainted(false);
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; margin: 8,18,8,18");
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(survol); }
            @Override public void mouseExited(MouseEvent e) { b.setBackground(fond); }
        });
        return b;
    }

    /** Bouton lune / soleil qui bascule entre mode sombre et mode clair. */
    static JButton boutonMode() {
        JButton b = boutonSecondaire("");
        b.putClientProperty(FlatClientProperties.STYLE,
                "arc: 999; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; margin: 7,14,7,14");
        b.setIcon(new IconeMode());
        b.setIconTextGap(8);
        Runnable maj = () -> {
            b.setText(estSombre() ? "Mode clair" : "Mode sombre");
            b.setToolTipText("Passer en " + (estSombre() ? "mode clair" : "mode sombre") + " (toutes les fenêtres)");
        };
        maj.run();
        auChangement(maj);
        b.addActionListener(e -> basculer());
        return b;
    }

    /** Soleil en mode sombre (on propose le clair), lune en mode clair. */
    static final class IconeMode implements Icon {
        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(x, y);
            g.setColor(c.getForeground());
            if (estSombre()) {
                g.fill(new Ellipse2D.Double(4.5, 4.5, 7, 7));
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    g.draw(new Line2D.Double(8 + Math.cos(a) * 5.6, 8 + Math.sin(a) * 5.6,
                                             8 + Math.cos(a) * 7.4, 8 + Math.sin(a) * 7.4));
                }
            } else {
                Area lune = new Area(new Ellipse2D.Double(1.5, 1.5, 13, 13));
                lune.subtract(new Area(new Ellipse2D.Double(5.5, -1.5, 12, 12)));
                g.fill(lune);
            }
            g.dispose();
        }
    }

    /**
     * En-tête commun des apps : petit surtitre "CENTRALE ÉTUDIANT", titre de la page,
     * infos facultatives en dessous, et le bouton de mode à droite.
     */
    static JPanel entete(String page, JComponent infos) {
        return entete(page, infos, new JComponent[0]);
    }

    /** Même en-tête, avec des composants supplémentaires à droite (avant le bouton de mode). */
    static JPanel entete(String page, JComponent infos, JComponent... aDroite) {
        JLabel surtitre = new JLabel("CENTRALE ÉTUDIANT");
        surtitre.setFont(FONT_SURTITRE);
        surtitre.setForeground(ACCENT);

        JLabel titre = new JLabel(page);
        titre.setFont(FONT_TITLE);
        titre.setForeground(TEXT_PRIMARY);

        JPanel gauche = new JPanel(new GridLayout(infos == null ? 2 : 3, 1, 0, 2));
        gauche.setOpaque(false);
        gauche.add(surtitre);
        gauche.add(titre);
        if (infos != null) gauche.add(infos);

        JPanel droite = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        droite.setOpaque(false);
        for (JComponent c : aDroite) droite.add(c);
        droite.add(boutonMode());

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BACKGROUND);
        p.setBorder(new EmptyBorder(16, 24, 14, 24));
        p.add(gauche, BorderLayout.CENTER);
        p.add(droite, BorderLayout.EAST);
        return p;
    }

    /** Petite ligne d'information grise (sous le titre d'une page). */
    static JLabel info(String texte) {
        JLabel l = new JLabel(texte);
        l.setFont(FONT_TABLE);
        l.setForeground(TEXT_DISCRET);
        return l;
    }

    static void styleLabel(JLabel label) {
        label.setFont(FONT_LABEL);
        label.setForeground(TEXT_PRIMARY);
    }

    /** Champ de saisie : bordure arrondie FlatLaf, couleurs du thème. */
    static void styleChamp(JTextField field) {
        field.setFont(FONT_LABEL);
        field.setBackground(SURFACE_ALT);
        field.setForeground(TEXT_PRIMARY);
        field.setCaretColor(TEXT_PRIMARY);
        field.putClientProperty(FlatClientProperties.STYLE, "margin: 5,8,5,8");
    }

    /** Tableau épuré : lignes alternées, pas de traits verticaux, en-tête discret. */
    static void styleTable(JTable table) {
        table.setFont(FONT_TABLE);
        table.setForeground(TEXT_PRIMARY);
        table.setBackground(SURFACE);
        table.setGridColor(BORDER);
        table.setRowHeight(34);
        table.setSelectionBackground(ACCENT);
        table.setSelectionForeground(TEXT_ON_ACCENT);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);

        JTableHeader header = table.getTableHeader();
        header.setFont(FONT_HEADER);
        header.setBackground(SURFACE);
        header.setForeground(TEXT_DISCRET);
        header.putClientProperty(FlatClientProperties.STYLE, "height: 34");
    }

    /**
     * Met un contenu (tableau, zone de texte, dessin...) dans un cadre arrondi.
     * Les scrolls et leurs contenus deviennent transparents pour que les coins arrondis restent propres.
     */
    static PanneauArrondi encadrer(JComponent contenu) {
        PanneauArrondi cadre = new PanneauArrondi(new BorderLayout(), 16);
        cadre.setBackground(SURFACE);
        if (contenu instanceof JScrollPane scroll) {
            scroll.setBorder(new EmptyBorder(1, 1, 1, 1));
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            if (scroll.getViewport().getView() instanceof JComponent vue) vue.setOpaque(false);
            if (scroll.getColumnHeader() != null) scroll.getColumnHeader().setOpaque(false);
            if (scroll.getViewport().getView() instanceof JTable table) table.getTableHeader().setOpaque(false);
        } else {
            contenu.setOpaque(false);
        }
        cadre.add(contenu, BorderLayout.CENTER);
        return cadre;
    }

    /**
     * Panneau à coins arrondis, avec bordure fine et bande de couleur à gauche (facultative).
     * Les composants qu'il contient sont coupés aux coins arrondis.
     */
    static class PanneauArrondi extends JPanel {
        private final int arc;
        private Color bande;
        private int largeurBande = 5;
        private boolean bordure = true;

        PanneauArrondi(LayoutManager layout, int arc) {
            super(layout);
            this.arc = arc;
            setOpaque(false);
            setBackground(SURFACE);
        }

        void setBande(Color couleur, int largeur) {
            this.bande = couleur;
            this.largeurBande = largeur;
            repaint();
        }

        void setBordure(boolean bordure) {
            this.bordure = bordure;
            repaint();
        }

        private Shape forme() {
            return new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape f = forme();
            g.setColor(getBackground());
            g.fill(f);
            if (bande != null) {
                g.clip(f);
                g.setColor(bande);
                g.fillRect(0, 0, largeurBande, getHeight());
            }
            g.dispose();
        }

        @Override
        protected void paintChildren(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.clip(forme());
            super.paintChildren(g);
            g.dispose();
        }

        @Override
        protected void paintBorder(Graphics g0) {
            super.paintBorder(g0);
            if (!bordure) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BORDER);
            g.draw(forme());
            g.dispose();
        }
    }

    // =====================================================================
    // PETITS COMPOSANTS MODERNES (pastilles, barres, titres de section)
    // =====================================================================

    /** Pastille arrondie : texte coloré sur fond de la même couleur, très transparent. */
    static final class Pastille extends JLabel {
        private Color couleur;

        Pastille(String texte, Color couleur) {
            super(texte);
            setFont(FONT_SURTITRE.deriveFont(12f));
            setBorder(new EmptyBorder(4, 10, 4, 10));
            setOpaque(false);
            changer(texte, couleur);
        }

        void changer(String texte, Color c) {
            couleur = c;
            setText(texte);
            setForeground(c);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = getHeight();
            g.setColor(transparent(couleur, 38));
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, h - 1, h, h));
            g.setColor(transparent(couleur, 70));
            g.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 2, h - 2, h, h));
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** Barre de progression arrondie (ex : moyenne sur 20). */
    static final class Barre extends JComponent {
        private double ratio;
        private Color couleur;
        private final int epaisseur;

        Barre(double ratio, Color couleur) {
            this(ratio, couleur, 6);
        }

        Barre(double ratio, Color couleur, int epaisseur) {
            this.epaisseur = epaisseur;
            setPreferredSize(new Dimension(90, epaisseur + 2));
            setOpaque(false);
            changer(ratio, couleur);
        }

        void changer(double r, Color c) {
            ratio = Double.isNaN(r) ? 0 : Math.max(0, Math.min(1, r));
            couleur = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = epaisseur, y = (getHeight() - h) / 2;
            g.setColor(BORDER);
            g.fill(new RoundRectangle2D.Double(0, y, getWidth(), h, h, h));
            if (ratio > 0) {
                g.setColor(couleur);
                g.fill(new RoundRectangle2D.Double(0, y, Math.max(h, getWidth() * ratio), h, h, h));
            }
            g.dispose();
        }
    }

    /** Petit titre de section en majuscules discrètes (« UNITÉS D'ENSEIGNEMENT »). */
    static JLabel surtitre(String texte) {
        JLabel l = new JLabel(texte.toUpperCase());
        l.setFont(FONT_SURTITRE);
        l.setForeground(TEXT_DISCRET);
        return l;
    }

    /** Carte : panneau arrondi de surface, avec marge intérieure. */
    static PanneauArrondi carte(LayoutManager layout, int marge) {
        PanneauArrondi p = new PanneauArrondi(layout, 18);
        p.setBackground(SURFACE);
        p.setBorder(new EmptyBorder(marge, marge, marge, marge));
        return p;
    }

    /** Petites icônes vectorielles (la police Inter n'a pas ▸ ▾ ✎) : dessinées avec la couleur du composant. */
    enum Icone implements Icon {
        CHEVRON_DROITE, CHEVRON_BAS, CRAYON, CROIX;

        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            g.setColor(c.getForeground());
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (this) {
                case CHEVRON_DROITE -> {
                    g.draw(new Line2D.Double(5.5, 3.5, 9.5, 7));
                    g.draw(new Line2D.Double(9.5, 7, 5.5, 10.5));
                }
                case CHEVRON_BAS -> {
                    g.draw(new Line2D.Double(3.5, 5.5, 7, 9.5));
                    g.draw(new Line2D.Double(7, 9.5, 10.5, 5.5));
                }
                case CRAYON -> {
                    java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                    p.moveTo(9.3, 2.4);
                    p.lineTo(11.6, 4.7);
                    p.lineTo(5, 11.3);
                    p.lineTo(2.3, 11.7);
                    p.lineTo(2.7, 9);
                    p.closePath();
                    g.draw(p);
                    g.draw(new Line2D.Double(8, 3.7, 10.3, 6));
                }
                case CROIX -> {
                    g.draw(new Line2D.Double(3.5, 3.5, 10.5, 10.5));
                    g.draw(new Line2D.Double(10.5, 3.5, 3.5, 10.5));
                }
            }
            g.dispose();
        }
    }

    /** Bouton discret sans fond, avec une icône, coloré au survol. */
    static JButton boutonIcone(Icon icone, String infobulle, Color survol) {
        JButton b = new JButton(icone);
        b.setForeground(TEXT_DISCRET);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(4, 6, 4, 6));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.setToolTipText(infobulle);
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setForeground(survol); }
            @Override public void mouseExited(MouseEvent e) { b.setForeground(TEXT_DISCRET); }
        });
        return b;
    }
}
