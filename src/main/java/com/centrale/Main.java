package com.centrale;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * Tableau de bord de Centrale Étudiant : un résumé de chaque programme
 * (cours du jour, devoirs urgents, moyennes, mails non lus),
 * et une barre de menus pour ouvrir chaque programme dans sa propre fenêtre.
 */
public class Main {

    static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter JOUR_LONG = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);

    /** Nombre de jours à l'avance pour qu'un devoir soit considéré comme "urgent". */
    static final int JOURS_URGENTS = 7;
    /** Nombre de mails non lus affichés dans la mini boîte de réception. */
    static final int MAX_MAILS = 5;

    static JFrame frame;
    static Carte carteEdt, carteDevoirs, carteMoyennes, carteMails;
    static Voyant voyantMails;
    static JLabel compteurMails;
    static JLabel miseAJour;
    static JMenu menuMails;
    static long dernierRafraichissementLocal = 0;

    /** Dernier emploi du temps chargé : la carte est redessinée chaque minute sans retélécharger. */
    static Calendar.Edt edt;
    /** Derniers mails non lus dans Gmail : filtrés à nouveau quand un mail est ouvert dans la boîte intégrée. */
    static List<Mails.Mail> mailsNonLus;

    // =====================================================================
    // COMPOSANTS COMMUNS
    // =====================================================================

    /** Petit voyant rond : clignote quand il y a quelque chose à voir. */
    static final class Voyant extends JComponent {
        private Color couleur = Theme.TEXT_DISCRET;
        private boolean allume = true;
        private final Timer timer = new Timer(650, e -> { allume = !allume; repaint(); });

        Voyant(int taille) {
            Dimension d = new Dimension(taille, taille);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            setOpaque(false);
        }

        void etat(Color c, boolean clignoter, String info) {
            couleur = c;
            setToolTipText(info);
            if (clignoter) {
                timer.start();
            } else {
                timer.stop();
                allume = true;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double t = Math.min(getWidth(), getHeight());
            double x = (getWidth() - t) / 2, y = (getHeight() - t) / 2;
            if (allume) {
                // halo
                g.setColor(new Color(couleur.getRed(), couleur.getGreen(), couleur.getBlue(), 70));
                g.fill(new Ellipse2D.Double(x, y, t, t));
                g.setColor(couleur);
            } else {
                g.setColor(new Color(couleur.getRed(), couleur.getGreen(), couleur.getBlue(), 90));
            }
            g.fill(new Ellipse2D.Double(x + t * 0.2, y + t * 0.2, t * 0.6, t * 0.6));
            g.dispose();
        }
    }

    /** Barre de progression arrondie (moyenne sur 20). */
    static final class Barre extends JComponent {
        private final double ratio;
        private final Color couleur;

        Barre(double ratio, Color couleur) {
            this.ratio = Math.max(0, Math.min(1, ratio));
            this.couleur = couleur;
            setPreferredSize(new Dimension(90, 6));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = 6, y = (getHeight() - h) / 2;
            g.setColor(Theme.BORDER);
            g.fill(new RoundRectangle2D.Double(0, y, getWidth(), h, h, h));
            g.setColor(couleur);
            g.fill(new RoundRectangle2D.Double(0, y, Math.max(h, getWidth() * ratio), h, h, h));
            g.dispose();
        }
    }

    /** Contenu d'une carte qui suit la largeur du scroll (le texte est coupé au lieu de déborder). */
    static final class ListePanel extends JPanel implements Scrollable {
        ListePanel() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 32; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Une carte du tableau de bord : titre, lien "Ouvrir", puis une liste de lignes. */
    static final class Carte extends Theme.PanneauArrondi {
        final JPanel titre;
        final ListePanel corps = new ListePanel();

        Carte(String nom, String lien, Runnable ouvrir) {
            super(new BorderLayout(), 18);
            setBackground(Theme.SURFACE);

            JLabel label = new JLabel(nom);
            label.setFont(Theme.FONT_SECTION);
            label.setForeground(Theme.TEXT_PRIMARY);

            titre = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            titre.setOpaque(false);
            titre.add(label);

            JButton bouton = lien(lien + "  →", ouvrir);

            JPanel entete = new JPanel(new BorderLayout());
            entete.setOpaque(false);
            entete.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                    new EmptyBorder(12, 10, 12, 14)));
            entete.add(titre, BorderLayout.WEST);
            entete.add(bouton, BorderLayout.EAST);

            JScrollPane scroll = new JScrollPane(corps);
            scroll.setBorder(new EmptyBorder(10, 14, 10, 14));
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(16);

            add(entete, BorderLayout.NORTH);
            add(scroll, BorderLayout.CENTER);
        }

        void message(String texte, Color couleur) {
            JLabel l = new JLabel("<html><div style='text-align:center'>" + echapper(texte).replace("\n", "<br>") + "</div></html>",
                    SwingConstants.CENTER);
            l.setFont(Theme.FONT_TABLE);
            l.setForeground(couleur);
            l.setBorder(new EmptyBorder(24, 8, 24, 8));
            remplir(null, List.of(l));
        }

        void remplir(JComponent enTete, List<? extends JComponent> lignes) {
            corps.removeAll();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTH;
            gbc.insets = new Insets(0, 0, 6, 0);

            if (enTete != null) {
                corps.add(enTete, gbc);
                gbc.gridy++;
            }
            for (JComponent c : lignes) {
                corps.add(c, gbc);
                gbc.gridy++;
            }

            // pousse tout vers le haut
            gbc.weighty = 1;
            gbc.fill = GridBagConstraints.BOTH;
            JPanel remplissage = new JPanel();
            remplissage.setOpaque(false);
            corps.add(remplissage, gbc);

            corps.revalidate();
            corps.repaint();
        }
    }

    static JButton lien(String texte, Runnable action) {
        JButton b = new JButton(texte);
        b.setFont(Theme.FONT_BUTTON);
        b.setForeground(Theme.ACCENT);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(2, 4, 2, 4));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setForeground(Theme.TEXT_PRIMARY); }
            @Override public void mouseExited(MouseEvent e) { b.setForeground(Theme.ACCENT); }
        });
        b.addActionListener(e -> action.run());
        return b;
    }

    static JLabel label(String texte, Font font, Color couleur) {
        JLabel l = new JLabel(texte);
        l.setFont(font);
        l.setForeground(couleur);
        return l;
    }

    /** Ligne d'une carte : bande de couleur à gauche, survol, et clic pour ouvrir le programme. */
    static JPanel ligne(Color bande, Runnable auClic) {
        Theme.PanneauArrondi p = new Theme.PanneauArrondi(new BorderLayout(10, 0), 12);
        p.setBackground(Theme.SURFACE_ALT);
        p.setBordure(false);
        p.setBande(bande, 4);
        p.setBorder(new EmptyBorder(8, 14, 8, 12));
        if (auClic != null) {
            p.setCursor(new Cursor(Cursor.HAND_CURSOR));
            p.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { p.setBackground(Theme.SURFACE_HOVER); }
                @Override public void mouseExited(MouseEvent e) {
                    if (!p.contains(e.getPoint())) p.setBackground(Theme.SURFACE_ALT);
                }
                @Override public void mouseClicked(MouseEvent e) { auClic.run(); }
            });
        }
        return p;
    }

    /** Titre + sous-titre l'un sous l'autre (coupés avec "..." si trop longs). */
    static JPanel deuxLignes(String haut, Color couleurHaut, String bas) {
        JPanel p = new JPanel(new GridLayout(bas == null ? 1 : 2, 1, 0, 1));
        p.setOpaque(false);
        p.add(label(haut, Theme.FONT_TABLE.deriveFont(Font.BOLD), couleurHaut));
        if (bas != null) p.add(label(bas, Theme.FONT_PETIT, Theme.TEXT_DISCRET));
        return p;
    }

    static String echapper(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String dateDuJour() {
        return majuscule(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)));
    }

    static String majuscule(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Même couleur par nom que dans Calendar.java / Devoirs.java (teintes réparties sur les noms triés). */
    static Map<String, Color> couleursPour(List<String> noms) {
        List<String> tries = noms.stream().distinct().sorted().toList();
        Map<String, Color> res = new HashMap<>();
        for (int i = 0; i < tries.size(); i++) {
            res.put(tries.get(i), Theme.teinte((float) i / tries.size()));
        }
        return res;
    }

    // =====================================================================
    // OUVERTURE DES PROGRAMMES (chacun dans sa propre fenêtre)
    // =====================================================================

    /**
     * Ouvre un programme, ou ramène sa fenêtre au premier plan s'il est déjà ouvert.
     * Le main() du programme est lancé hors de l'interface : la connexion BDD / internet ne fige pas le tableau de bord.
     */
    static void ouvrir(Supplier<JFrame> fenetre, Runnable lancer) {
        JFrame f = fenetre.get();
        if (f != null && f.isDisplayable()) {
            f.setState(JFrame.NORMAL);
            f.toFront();
            f.requestFocus();
            return;
        }
        new Thread(() -> {
            try {
                lancer.run();
            } catch (Exception | ExceptionInInitializerError e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
                        "Impossible d'ouvrir ce programme :\n" + e, "Erreur", JOptionPane.ERROR_MESSAGE));
            }
        }, "ouverture-programme").start();
    }

    /** Paramètres modifiés : on recharge tout le tableau de bord avec les nouvelles infos. */
    static void ouvrirParametres() {
        if (Config.ouvrirParametres(frame, false)) toutActualiser();
    }

    static void ouvrirEmploiDuTemps() { ouvrir(() -> Calendar.fenetre, () -> Calendar.main(new String[0])); }
    static void ouvrirNotes()         { ouvrir(() -> Note.fenetre, () -> Note.main(new String[0])); }
    static void ouvrirDevoirs()       { ouvrir(() -> Devoirs.fenetre, () -> Devoirs.main(new String[0])); }
    static void ouvrirMails()         { ouvrir(() -> Mails.fenetre, () -> Mails.main(new String[0])); }

    // =====================================================================
    // CARTE 1 : COURS DU JOUR
    //
    // Les cours d'aujourd'hui tant qu'il en reste ; une fois le dernier terminé
    // (ou s'il n'y a pas cours), ceux du prochain jour de cours.
    // =====================================================================
    static void chargerEmploiDuTemps() {
        carteEdt.message("Chargement de l'emploi du temps...", Theme.TEXT_DISCRET);

        new SwingWorker<Calendar.Edt, Void>() {
            @Override
            protected Calendar.Edt doInBackground() {
                return Calendar.charger(); // sans internet : dernière sauvegarde
            }

            @Override
            protected void done() {
                try {
                    edt = get();
                } catch (Exception e) {
                    edt = null;
                    carteEdt.message("Impossible de récupérer l'emploi du temps.\n" + cause(e), Theme.RETARD);
                    return;
                }
                afficherEmploiDuTemps();
            }
        }.execute();
    }

    static void afficherEmploiDuTemps() {
        if (edt == null) return;
        List<Evenement> tous = edt.evenements();
        Map<String, Color> couleurs = couleursPour(tous.stream().map(Evenement::getTitre).toList());
        LocalDateTime maintenant = LocalDateTime.now();
        LocalDate aujourdhui = maintenant.toLocalDate();

        List<Evenement> duJour = coursDu(tous, aujourdhui);
        boolean resteAujourdhui = duJour.stream().anyMatch(e -> e.getFin().isAfter(maintenant));
        boolean finiAujourdhui = !duJour.isEmpty() && !resteAujourdhui;

        // Plus de cours aujourd'hui : prochain jour de cours (dans les deux semaines)
        LocalDate jour = aujourdhui;
        if (!resteAujourdhui) {
            duJour = List.of();
            for (int i = 1; duJour.isEmpty() && i <= 14; i++) {
                jour = aujourdhui.plusDays(i);
                duJour = coursDu(tous, jour);
            }
        }

        if (duJour.isEmpty()) {
            carteEdt.message((finiAujourdhui ? "Journée terminée. " : "")
                    + "Aucun cours prévu dans les deux prochaines semaines."
                    + (edt.horsLigne() ? "\n" + edt.libelleHorsLigne() : ""), Theme.TEXT_DISCRET);
            return;
        }

        String titre;
        Color couleurTitre = Theme.ACCENT;
        if (jour.equals(aujourdhui)) {
            titre = "Aujourd'hui · " + jour.format(JOUR_LONG);
        } else {
            String quand = jour.equals(aujourdhui.plusDays(1)) ? "Demain" : majuscule(jour.format(JOUR_LONG));
            titre = (finiAujourdhui ? "Journée terminée · " : "Pas cours aujourd'hui · ")
                    + quand + (jour.equals(aujourdhui.plusDays(1)) ? " · " + jour.format(JOUR_LONG) : "");
            couleurTitre = Theme.ATTENTION;
        }

        JPanel enTete = new JPanel(new BorderLayout(8, 0));
        enTete.setOpaque(false);
        enTete.setBorder(new EmptyBorder(0, 2, 4, 0));
        enTete.add(label(majuscule(titre), Theme.FONT_BUTTON, couleurTitre), BorderLayout.CENTER);
        if (edt.horsLigne()) {
            JLabel horsLigne = label(edt.libelleHorsLigne(), Theme.FONT_PETIT, Theme.ATTENTION);
            horsLigne.setToolTipText(edt.erreur());
            enTete.add(horsLigne, BorderLayout.EAST);
        }

        List<JComponent> lignes = new ArrayList<>();
        for (Evenement e : duJour) {
            boolean termine = !e.getFin().isAfter(maintenant);
            boolean enCours = !termine && !e.getDebut().isAfter(maintenant);

            // Cours terminé : grisé ; cours en cours : signalé à droite
            Color bande = termine ? Theme.BORDER : couleurs.getOrDefault(e.getTitre(), Theme.ACCENT);
            JPanel l = ligne(bande, Main::ouvrirEmploiDuTemps);
            JLabel horaire = label(e.getDebut().format(HEURE) + " – " + e.getFin().format(HEURE),
                    Theme.FONT_TABLE.deriveFont(Font.BOLD), Theme.TEXT_DISCRET);
            horaire.setPreferredSize(new Dimension(100, horaire.getPreferredSize().height));
            String salle = (e.getSalle() == null) ? "" : e.getSalle().replace("\n", " ").trim();
            l.add(horaire, BorderLayout.WEST);
            l.add(deuxLignes(e.getTitre(), termine ? Theme.TEXT_DISCRET : Theme.TEXT_PRIMARY,
                    salle.isEmpty() ? null : salle), BorderLayout.CENTER);
            if (enCours) {
                l.add(label("En cours", Theme.FONT_BUTTON, Theme.SUCCES), BorderLayout.EAST);
            } else if (termine) {
                l.add(label("Terminé", Theme.FONT_PETIT, Theme.TEXT_DISCRET), BorderLayout.EAST);
            }
            lignes.add(l);
        }
        carteEdt.remplir(enTete, lignes);
    }

    static List<Evenement> coursDu(List<Evenement> tous, LocalDate jour) {
        return tous.stream()
                .filter(e -> e.getDebut().toLocalDate().equals(jour))
                .sorted(Comparator.comparing(Evenement::getDebut))
                .toList();
    }

    // =====================================================================
    // CARTE 2 : DEVOIRS URGENTS
    // =====================================================================
    static void chargerDevoirs() {
        new SwingWorker<List<Devoirs.Devoir>, Void>() {
            @Override
            protected List<Devoirs.Devoir> doInBackground() {
                Devoirs.Database.synchroniser(); // BDD <-> CSV (si en ligne), comme dans Devoirs.java
                return Devoirs.DevoirCsv.listerVisibles();
            }

            @Override
            protected void done() {
                List<Devoirs.Devoir> tous;
                try {
                    tous = get();
                } catch (Exception e) {
                    carteDevoirs.message("Impossible de lire les devoirs.\n" + cause(e), Theme.RETARD);
                    return;
                }
                Map<String, Color> couleurs = couleursPour(tous.stream().map(d -> d.matiere).toList());

                LocalDate aujourdhui = LocalDate.now();
                LocalDate limite = aujourdhui.plusDays(JOURS_URGENTS);
                List<Devoirs.Devoir> aFaire = tous.stream().filter(d -> !d.fait).sorted(Devoirs.PAR_ECHEANCE).toList();
                List<Devoirs.Devoir> urgents = aFaire.stream().filter(d -> !d.pourLe.isAfter(limite)).toList();

                if (urgents.isEmpty()) {
                    String suite = aFaire.isEmpty() ? "Aucun devoir à faire."
                            : "Rien d'urgent cette semaine.\nProchain devoir : " + aFaire.get(0).matiere
                              + " pour le " + aFaire.get(0).pourLe.format(Devoirs.SAISIE);
                    carteDevoirs.message(suite, Theme.SUCCES);
                    return;
                }

                long enRetard = urgents.stream().filter(d -> d.pourLe.isBefore(aujourdhui)).count();
                String resume = urgents.size() + " à rendre dans les " + JOURS_URGENTS + " jours"
                        + (enRetard > 0 ? "   ·   " + enRetard + " en retard" : "");
                JLabel enTete = label(resume, Theme.FONT_BUTTON, enRetard > 0 ? Theme.RETARD : Theme.ACCENT);
                enTete.setBorder(new EmptyBorder(0, 2, 4, 0));

                List<JComponent> lignes = new ArrayList<>();
                for (Devoirs.Devoir d : urgents) {
                    Color couleur = couleurs.getOrDefault(d.matiere, Theme.ACCENT);
                    JPanel l = ligne(couleur, Main::ouvrirDevoirs);

                    String consigne = d.description.replace("\n", " ").trim();
                    l.add(deuxLignes(d.matiere, couleur, consigne.isEmpty() ? null : consigne), BorderLayout.CENTER);
                    l.add(echeance(d.pourLe, aujourdhui), BorderLayout.EAST);
                    lignes.add(l);
                }
                carteDevoirs.remplir(enTete, lignes);
            }
        }.execute();
    }

    /** "En retard (2 j)", "Aujourd'hui", "Demain", "Dans 4 jours" : couleur selon l'urgence. */
    static JLabel echeance(LocalDate pourLe, LocalDate aujourdhui) {
        long jours = ChronoUnit.DAYS.between(aujourdhui, pourLe);
        String texte;
        Color couleur;
        if (jours < 0)       { texte = "En retard (" + (-jours) + " j)"; couleur = Theme.RETARD; }
        else if (jours == 0) { texte = "Aujourd'hui"; couleur = Theme.RETARD; }
        else if (jours == 1) { texte = "Demain"; couleur = Theme.ATTENTION; }
        else if (jours <= 3) { texte = "Dans " + jours + " jours"; couleur = Theme.ATTENTION; }
        else                 { texte = "Dans " + jours + " jours"; couleur = Theme.TEXT_DISCRET; }
        JLabel l = label(texte, Theme.FONT_BUTTON, couleur);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        return l;
    }

    // =====================================================================
    // CARTE 3 : MOYENNES PAR MATIÈRE
    // =====================================================================

    /** Moyenne d'une matière (null = pas encore de note) et coefficient de la matière. */
    record Moyenne(String matiere, Double valeur, int nbNotes, double coefficient) {}

    static void chargerMoyennes() {
        new SwingWorker<List<Moyenne>, Void>() {
            @Override
            protected List<Moyenne> doInBackground() {
                Note.Database.synchroniser(); // BDD <-> CSV (si en ligne), comme dans Note.java
                List<Note.CsvMirror.Entry> entrees = Note.CsvMirror.lire();

                // Même calcul que Note.calculMoyenneGenerale() : somme((note/quotient) x coef) / somme(coef) x 20
                List<Moyenne> res = new ArrayList<>();
                Map<String, Double> coefs = Note.CsvMirror.coefficientsMatieres();
                for (String matiere : coefs.keySet()) {
                    double sommePonderee = 0, sommeCoefs = 0;
                    int nb = 0;
                    for (Note.CsvMirror.Entry e : entrees) {
                        if (!e.estNote() || !e.matiere.equals(matiere) || e.statut.equals(Note.CsvMirror.DEL)) continue;
                        if (e.quotient <= 0) continue;
                        sommePonderee += ((double) e.valeur / e.quotient) * e.coefficient;
                        sommeCoefs += e.coefficient;
                        nb++;
                    }
                    res.add(new Moyenne(matiere, sommeCoefs > 0 ? sommePonderee / sommeCoefs * 20 : null, nb, coefs.get(matiere)));
                }
                return res;
            }

            @Override
            protected void done() {
                List<Moyenne> moyennes;
                try {
                    moyennes = get();
                } catch (Exception e) {
                    carteMoyennes.message("Impossible de lire les notes.\n" + cause(e), Theme.RETARD);
                    return;
                }
                if (moyennes.isEmpty()) {
                    carteMoyennes.message("Aucune matière pour l'instant.", Theme.TEXT_DISCRET);
                    return;
                }

                // Moyenne générale : moyennes des matières pondérées par leur coefficient (matières notées seulement)
                double somme = 0, nb = 0;
                for (Moyenne m : moyennes) {
                    if (m.valeur() != null) { somme += m.valeur() * m.coefficient(); nb += m.coefficient(); }
                }
                JPanel enTete = new JPanel(new BorderLayout());
                enTete.setOpaque(false);
                enTete.setBorder(new EmptyBorder(0, 2, 6, 2));
                enTete.add(label("Moyenne générale", Theme.FONT_BUTTON, Theme.ACCENT), BorderLayout.WEST);
                enTete.add(label(nb > 0 ? String.format("%.2f / 20", somme / nb) : "—",
                        Theme.FONT_SECTION, nb > 0 ? couleurMoyenne(somme / nb) : Theme.TEXT_DISCRET), BorderLayout.EAST);

                List<JComponent> lignes = new ArrayList<>();
                for (Moyenne m : moyennes) {
                    Color couleur = (m.valeur() == null) ? Theme.BORDER : couleurMoyenne(m.valeur());
                    JPanel l = ligne(couleur, Main::ouvrirNotes);
                    l.add(deuxLignes(m.matiere(), Theme.TEXT_PRIMARY,
                            m.nbNotes() + " note" + (m.nbNotes() > 1 ? "s" : "")
                            + "  ·  coef " + Note.formatCoef(m.coefficient())), BorderLayout.CENTER);

                    JPanel droite = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
                    droite.setOpaque(false);
                    if (m.valeur() != null) droite.add(new Barre(m.valeur() / 20.0, couleur));
                    JLabel valeur = label(m.valeur() == null ? "—" : String.format("%.2f", m.valeur()),
                            Theme.FONT_BUTTON.deriveFont(14f), m.valeur() == null ? Theme.TEXT_DISCRET : couleur);
                    valeur.setHorizontalAlignment(SwingConstants.RIGHT);
                    valeur.setPreferredSize(new Dimension(44, valeur.getPreferredSize().height));
                    droite.add(valeur);
                    l.add(droite, BorderLayout.EAST);
                    lignes.add(l);
                }
                carteMoyennes.remplir(enTete, lignes);
            }
        }.execute();
    }

    static Color couleurMoyenne(double m) {
        if (m >= 10) return Theme.SUCCES;
        if (m >= 8) return Theme.ATTENTION;
        return Theme.RETARD;
    }

    // =====================================================================
    // CARTE 4 : MINI BOÎTE DE RÉCEPTION (mails non lus + voyant)
    // =====================================================================
    static void chargerMails() {
        voyantMails.etat(Theme.TEXT_DISCRET, false, "Vérification des mails...");
        compteurMails.setText("");
        carteMails.message("Connexion à Gmail...", Theme.TEXT_DISCRET);

        new SwingWorker<List<Mails.Mail>, Void>() {
            @Override
            protected List<Mails.Mail> doInBackground() throws Exception {
                return Mails.recupererMails(true, false); // non lus, en-têtes seulement : rapide
            }

            @Override
            protected void done() {
                try {
                    mailsNonLus = get();
                } catch (Exception e) {
                    mailsNonLus = null;
                    voyantMails.etat(Theme.TEXT_DISCRET, false, "Mails indisponibles");
                    menuMails.setText("Mails");
                    carteMails.message("Impossible de récupérer les mails.\n" + cause(e), Theme.RETARD);
                    return;
                }
                afficherMails();
            }
        }.execute();
    }

    /** Seulement les mails pas encore vus (ni lus dans Gmail, ni ouverts dans la boîte intégrée). */
    static void afficherMails() {
        if (mailsNonLus == null) return;
        List<Mails.Mail> nonLus = mailsNonLus.stream().filter(m -> !m.dejaVu()).toList();
        if (nonLus.isEmpty()) {
            voyantMails.etat(Theme.SUCCES, false, "Aucun mail non lu");
            compteurMails.setText("");
            menuMails.setText("Mails");
            carteMails.message("Aucun mail non lu de l'université.", Theme.SUCCES);
            return;
        }

        // Voyant rouge qui clignote : il y a des mails à lire
        String info = nonLus.size() + " mail" + (nonLus.size() > 1 ? "s" : "") + " non lu" + (nonLus.size() > 1 ? "s" : "");
        voyantMails.etat(Theme.RETARD, true, info);
        compteurMails.setText(String.valueOf(nonLus.size()));
        menuMails.setText("Mails (" + nonLus.size() + ")");

        List<JComponent> lignes = new ArrayList<>();
        for (Mails.Mail m : nonLus.subList(0, Math.min(MAX_MAILS, nonLus.size()))) {
            JPanel l = ligne(Theme.ACCENT, Main::ouvrirMails);
            l.add(deuxLignes(m.expediteur, Theme.TEXT_PRIMARY, m.objet), BorderLayout.CENTER);
            JLabel date = label(Mails.dateCourte(m.date), Theme.FONT_PETIT, Theme.ACCENT);
            date.setVerticalAlignment(SwingConstants.TOP);
            l.add(date, BorderLayout.EAST);
            lignes.add(l);
        }
        if (nonLus.size() > MAX_MAILS) {
            JLabel plus = label("+ " + (nonLus.size() - MAX_MAILS) + " autre(s) non lu(s)", Theme.FONT_PETIT, Theme.TEXT_DISCRET);
            plus.setHorizontalAlignment(SwingConstants.CENTER);
            lignes.add(plus);
        }
        carteMails.remplir(null, lignes);
    }

    static String cause(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return (t.getMessage() == null) ? t.getClass().getSimpleName() : t.getMessage();
    }

    // =====================================================================
    // ACTUALISATION
    // =====================================================================
    static void toutActualiser() {
        chargerEmploiDuTemps();
        chargerDevoirs();
        chargerMoyennes();
        chargerMails();
        dernierRafraichissementLocal = System.currentTimeMillis();
        miseAJour.setText(dateDuJour() + "   ·   mis à jour à " + LocalDateTime.now().format(HEURE));
    }

    /** Notes et devoirs seulement (rapide) : appelé quand on revient sur le tableau de bord. */
    static void actualiserDonneesLocales() {
        if (System.currentTimeMillis() - dernierRafraichissementLocal < 10_000) return;
        dernierRafraichissementLocal = System.currentTimeMillis();
        chargerDevoirs();
        chargerMoyennes();
    }

    // =====================================================================
    // BARRE DE NAVIGATION (Calendrier, Devoirs, Mails, Notes, Raccourcis)
    // =====================================================================
    static JMenu menu(String nom, int mnemonique) {
        JMenu m = new JMenu(nom);
        m.setMnemonic(mnemonique);
        m.setFont(Theme.FONT_TABLE);
        return m;
    }

    static JMenuItem item(JMenu menu, String nom, KeyStroke raccourci, Runnable action) {
        JMenuItem i = new JMenuItem(nom);
        i.setFont(Theme.FONT_TABLE);
        if (raccourci != null) i.setAccelerator(raccourci);
        i.addActionListener(e -> action.run());
        menu.add(i);
        return i;
    }

    static void separateur(JMenu menu) {
        menu.addSeparator();
    }

    static KeyStroke ctrl(int touche) {
        return KeyStroke.getKeyStroke(touche, InputEvent.CTRL_DOWN_MASK);
    }

    static JMenuBar creerMenus() {
        JMenuBar barre = new JMenuBar();

        JMenu calendrier = menu("Calendrier", KeyEvent.VK_C);
        item(calendrier, "Ouvrir l'emploi du temps", ctrl(KeyEvent.VK_1), Main::ouvrirEmploiDuTemps);
        item(calendrier, "Actualiser", null, Main::chargerEmploiDuTemps);

        JMenu devoirs = menu("Devoirs", KeyEvent.VK_D);
        item(devoirs, "Ouvrir les devoirs", ctrl(KeyEvent.VK_2), Main::ouvrirDevoirs);
        item(devoirs, "Nouveau devoir...", ctrl(KeyEvent.VK_N), () -> {
            Devoirs.AddDevoir();
            dernierRafraichissementLocal = 0; // les devoirs seront relus au retour sur le tableau de bord
        });
        item(devoirs, "Actualiser", null, Main::chargerDevoirs);

        JMenu mails = menu("Mails", KeyEvent.VK_M);
        item(mails, "Ouvrir la boîte de réception", ctrl(KeyEvent.VK_3), Main::ouvrirMails);
        item(mails, "Vérifier les nouveaux mails", ctrl(KeyEvent.VK_M), Main::chargerMails);
        menuMails = mails;

        JMenu notes = menu("Notes", KeyEvent.VK_N);
        item(notes, "Ouvrir les notes", ctrl(KeyEvent.VK_4), Main::ouvrirNotes);
        item(notes, "Actualiser les moyennes", null, Main::chargerMoyennes);

        JMenu raccourcis = menu("Raccourcis", KeyEvent.VK_R);
        item(raccourcis, "Tout actualiser", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), Main::toutActualiser);
        item(raccourcis, "Mode clair / sombre", ctrl(KeyEvent.VK_T), Theme::basculer);
        item(raccourcis, "Paramètres (iCal, Gmail, base de données)...", ctrl(KeyEvent.VK_COMMA), Main::ouvrirParametres);
        item(raccourcis, "Liste des raccourcis clavier", null, () -> JOptionPane.showMessageDialog(frame,
                "F5  : tout actualiser\n"
                + "Ctrl+1 : calendrier\nCtrl+2 : devoirs\nCtrl+3 : mails\nCtrl+4 : notes\n"
                + "Ctrl+, : paramètres\nCtrl+T : mode clair / sombre\nCtrl+N : nouveau devoir\nCtrl+M : vérifier les nouveaux mails\nCtrl+Q : quitter",
                "Raccourcis clavier", JOptionPane.INFORMATION_MESSAGE));
        separateur(raccourcis);
        item(raccourcis, "Quitter", ctrl(KeyEvent.VK_Q), () -> System.exit(0));

        barre.add(calendrier);
        barre.add(devoirs);
        barre.add(mails);
        barre.add(notes);
        barre.add(raccourcis);
        return barre;
    }

    // =====================================================================
    // FENÊTRE PRINCIPALE
    // =====================================================================
    public static void main(String[] args) {

        Theme.installer();

        // Évite de figer l'appli plusieurs secondes si le Raspberry Pi est injoignable
        java.sql.DriverManager.setLoginTimeout(3);

        // Les programmes ouverts depuis le tableau de bord ferment seulement leur fenêtre
        Note.fermeture = JFrame.DISPOSE_ON_CLOSE;
        Devoirs.fermeture = JFrame.DISPOSE_ON_CLOSE;
        Calendar.fermeture = JFrame.DISPOSE_ON_CLOSE;
        Mails.fermeture = JFrame.DISPOSE_ON_CLOSE;

        SwingUtilities.invokeLater(() -> {
            // Tout premier lancement (ex : un ami qui vient d'installer l'appli) : lien iCal, Gmail...
            Config.demanderSiPremierLancement();

            frame = new JFrame("Centrale Étudiant · Tableau de bord");
            frame.setSize(1200, 820);
            frame.setMinimumSize(new Dimension(900, 620));
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().setBackground(Theme.BACKGROUND);
            frame.setJMenuBar(creerMenus());

            // --- en-tête ---
            miseAJour = Theme.info(dateDuJour());
            JPanel haut = Theme.entete("Tableau de bord", miseAJour);

            // --- les 4 cartes ---
            carteEdt = new Carte("Cours du jour", "Semaine", Main::ouvrirEmploiDuTemps);
            carteDevoirs = new Carte("Devoirs urgents", "Tous les devoirs", Main::ouvrirDevoirs);
            carteMoyennes = new Carte("Moyennes", "Mes notes", Main::ouvrirNotes);
            carteMails = new Carte("Boîte de réception", "Tous les mails", Main::ouvrirMails);

            voyantMails = new Voyant(14);
            compteurMails = label("", Theme.FONT_BUTTON, Theme.RETARD);
            carteMails.titre.add(voyantMails);
            carteMails.titre.add(compteurMails);

            JPanel grille = new JPanel(new GridLayout(2, 2, 16, 16));
            grille.setBackground(Theme.BACKGROUND);
            grille.setBorder(new EmptyBorder(0, 24, 24, 24));
            grille.add(carteEdt);
            grille.add(carteDevoirs);
            grille.add(carteMoyennes);
            grille.add(carteMails);

            JPanel panel = new JPanel(new BorderLayout());
            panel.setBackground(Theme.BACKGROUND);
            panel.add(haut, BorderLayout.NORTH);
            panel.add(grille, BorderLayout.CENTER);
            frame.getContentPane().add(panel);

            // Retour sur le tableau de bord (après avoir ajouté une note / un devoir) : on relit notes et devoirs
            frame.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    actualiserDonneesLocales();
                }
            });

            // Nouveaux mails vérifiés toutes les 5 minutes
            new Timer(5 * 60 * 1000, e -> chargerMails()).start();

            // Un mail ouvert dans la boîte intégrée n'est plus compté comme non lu
            Mails.Vus.ecouteurs.add(() -> SwingUtilities.invokeLater(Main::afficherMails));

            // Chaque minute : cours terminés grisés, passage au jour suivant après le dernier cours
            new Timer(60 * 1000, e -> afficherEmploiDuTemps()).start();

            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            toutActualiser();
        });
    }
}
