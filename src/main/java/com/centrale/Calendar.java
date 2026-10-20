package com.centrale;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;


import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;

public class Calendar {

    static JButton themedButton(String text) {
        return Theme.bouton(text);
    }

    static LocalDate jourActuelle() {
        return LocalDate.now();
    }

    // =====================================================================
    // SAUVEGARDE HORS-LIGNE
    //
    // Chaque emploi du temps téléchargé avec succès est copié dans edt_backup.ics
    // (dossier de l'utilisateur, voir Config). Sans internet, ou si le serveur ADE
    // ne répond pas, c'est cette dernière copie qui est affichée.
    // =====================================================================
    static final Path SAUVEGARDE = Config.fichier("edt_backup.ics");

    /**
     * Emploi du temps chargé.
     * @param sauvegardeDu null = téléchargé à l'instant ; sinon date de la copie hors-ligne affichée
     * @param erreur       raison pour laquelle le téléchargement a échoué (null si en ligne)
     */
    record Edt(List<Evenement> evenements, LocalDateTime sauvegardeDu, String erreur) {
        boolean horsLigne() {
            return sauvegardeDu != null;
        }

        /** "Hors-ligne · copie du 20/10 à 08:12" */
        String libelleHorsLigne() {
            return "Hors-ligne · copie du " + sauvegardeDu.format(DateTimeFormatter.ofPattern("dd/MM 'à' HH:mm"));
        }
    }

    /**
     * Télécharge l'emploi du temps (lien des Paramètres) et le sauvegarde ;
     * en cas d'échec, relit la dernière sauvegarde. Exception seulement s'il n'y a ni internet ni sauvegarde.
     */
    static Edt charger() {
        String lien = Config.get(Config.ICAL);
        if (lien == null || lien.isBlank()) {
            throw new IllegalStateException("Aucun lien iCal : renseigne-le dans les Paramètres.");
        }
        try {
            String ics = telecharger(lien);
            List<Evenement> evenements = convertirEvents(parseIcal(ics));
            sauvegarder(ics);
            return new Edt(evenements, null, null);
        } catch (IllegalStateException e) {
            if (!Files.exists(SAUVEGARDE)) throw e;
            try {
                String ics = Files.readString(SAUVEGARDE, StandardCharsets.UTF_8);
                LocalDateTime date = LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(SAUVEGARDE).toInstant(), ZoneId.systemDefault());
                return new Edt(convertirEvents(parseIcal(ics)), date, e.getMessage());
            } catch (IOException | RuntimeException lecture) {
                throw e; // sauvegarde illisible : on garde l'erreur d'origine
            }
        }
    }

    /** Écriture atomique : une sauvegarde à moitié écrite ne remplace jamais la précédente. */
    private static void sauvegarder(String ics) {
        try {
            Path tmp = SAUVEGARDE.resolveSibling(SAUVEGARDE.getFileName() + ".tmp");
            Files.writeString(tmp, ics, StandardCharsets.UTF_8);
            Files.move(tmp, SAUVEGARDE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Sauvegarde de l'emploi du temps impossible : " + e.getMessage());
        }
    }

    /** Emploi du temps depuis le lien iCal des paramètres (voir Config), avec secours hors-ligne. */
    static List<Evenement> requestHttp() {
        return charger().evenements();
    }

    /** Télécharge et lit un lien iCal, sans sauvegarde (utilisé aussi par le bouton "Tester" des Paramètres). */
    static List<Evenement> requestHttp(String lien) {
        return convertirEvents(parseIcal(telecharger(lien)));
    }

    private static String telecharger(String lien) {
        if (lien == null || lien.isBlank()) {
            throw new IllegalStateException("Aucun lien iCal : renseigne-le dans les Paramètres.");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.normaliserIcal(lien)))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Lien iCal invalide : " + lien, e);
        }
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Le serveur a répondu " + response.statusCode() + " (lien iCal expiré ?)");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Téléchargement interrompu", e);
        } catch (IOException e) {
            throw new IllegalStateException("Pas de connexion au serveur de l'emploi du temps", e);
        }
    }

    static List<VEvent> parseIcal(String icalContent) {
        ICalendar ical = Biweekly.parse(icalContent).first();
        if (ical == null) throw new IllegalStateException("Ce lien ne renvoie pas un calendrier iCal (.ics)");
        return ical.getEvents();
    }

    static List<Evenement> convertirEvents(List<VEvent> vevents) {
        List<Evenement> evenements = new ArrayList<>();
        for (VEvent v : vevents) {
            evenements.add(new Evenement(
                    v.getSummary().getValue(),
                    v.getDateStart().getValue().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    v.getDateEnd().getValue().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    v.getLocation() != null ? v.getLocation().getValue() : ""
            ));
        }
        return evenements;
    }

    // =====================================================================
    // COULEURS PAR MATIÈRE (même titre = même couleur, sur tout l'emploi du temps)
    // =====================================================================
    private static final Map<String, Color> couleurs = new HashMap<>();

    static void attribuerCouleurs(List<Evenement> evenements) {
        List<String> titres = evenements.stream()
                .map(Evenement::getTitre)
                .distinct()
                .sorted()
                .toList();

        for (int i = 0; i < titres.size(); i++) {
            float teinte = (float) i / titres.size();
            couleurs.put(titres.get(i), Color.getHSBColor(teinte, 0.42f, 0.96f));
        }
    }

    // =====================================================================
    // VUE SEMAINE EN GRILLE (façon ADE) : jours en colonnes, heures en lignes
    // =====================================================================
    static class VueSemaine extends JPanel {
        private static final int MARGE_GAUCHE = 56;
        private static final int HAUT_ENTETE = 46;

        private static final Color GRILLE = Theme.BORDER;
        private static final Color GRILLE_LEGERE = Theme.GRILLE_LEGERE;
        private static final Color ENTETE = Theme.SURFACE_ALT;
        private static final Color FOND_AUJOURDHUI = Theme.FOND_AUJOURDHUI;
        private static final Color ACCENT = Theme.ACCENT;
        private static final Color TEXTE_DISCRET = Theme.TEXT_DISCRET;
        private static final Color TRAIT_MAINTENANT = Theme.RETARD;
        /** Texte des cours : toujours foncé, les blocs restent pastel dans les deux modes. */
        private static final Color TEXTE_BLOC = new Color(0x1E1E2E);
        private static final Color TEXTE_BLOC_DISCRET = new Color(0x3A3A4E);
        private static final BasicStroke TRAIT = new BasicStroke(1f);
        private static final BasicStroke POINTILLES = new BasicStroke(1f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[]{2f, 4f}, 0f);
        private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");

        /** Zone d'un bloc à l'écran, gardée pour l'infobulle au survol. */
        private static final class Bloc {
            final Rectangle2D.Double zone;
            final Evenement evenement;

            Bloc(Rectangle2D.Double zone, Evenement evenement) {
                this.zone = zone;
                this.evenement = evenement;
            }
        }

        private final List<Evenement> evenements;
        private final List<Bloc> blocs = new ArrayList<>();
        private LocalDate lundi;
        private int heureDebut = 8;
        private int heureFin = 19;
        private int nbJours = 5;

        VueSemaine(List<Evenement> evenements) {
            this.evenements = evenements;
            setBackground(Theme.SURFACE);
            setToolTipText(""); // active les infobulles (voir getToolTipText)
            allerA(LocalDate.now());
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1000, 720);
        }

        void allerA(LocalDate jour) {
            lundi = jour.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            calculerPlage();
            repaint();
        }

        void semaineSuivante() {
            allerA(lundi.plusWeeks(1));
        }

        void semainePrecedente() {
            allerA(lundi.minusWeeks(1));
        }

        String libelleSemaine() {
            DateTimeFormatter court = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH);
            DateTimeFormatter long_ = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
            LocalDate fin = lundi.plusDays(nbJours - 1);
            return "Semaine du " + lundi.format(court) + " au " + fin.format(long_);
        }

        /** Plage d'heures : 8h-19h par défaut, élargie si un cours dépasse. Samedi/dimanche affichés seulement s'il y a cours. */
        private void calculerPlage() {
            heureDebut = 8;
            heureFin = 19;
            nbJours = 5;
            for (Evenement e : evenements) {
                LocalDate d = e.getDebut().toLocalDate();
                if (d.isBefore(lundi) || d.isAfter(lundi.plusDays(6))) continue;

                nbJours = Math.max(nbJours, d.getDayOfWeek().getValue());
                heureDebut = Math.min(heureDebut, e.getDebut().getHour());

                LocalDateTime fin = e.getFin();
                int hf;
                if (!fin.toLocalDate().equals(d)) hf = 24;
                else hf = (fin.getMinute() > 0) ? fin.getHour() + 1 : fin.getHour();
                heureFin = Math.max(heureFin, Math.min(hf, 24));
            }
            if (heureFin <= heureDebut) heureFin = heureDebut + 1;
        }

        private List<Evenement> evenementsDuJour(LocalDate jour) {
            List<Evenement> liste = new ArrayList<>();
            for (Evenement e : evenements) {
                if (e.getDebut().toLocalDate().equals(jour)) liste.add(e);
            }
            liste.sort(Comparator.comparing(Evenement::getDebut).thenComparing(Evenement::getFin));
            return liste;
        }

        /**
         * Cours qui se chevauchent : on les met côte à côte.
         * @return pour chaque cours {numéro de colonne, nombre de colonnes du groupe}
         */
        private static int[][] disposer(List<Evenement> liste) {
            int[][] res = new int[liste.size()][2];
            List<LocalDateTime> finsColonnes = new ArrayList<>();
            int debutGroupe = 0;
            LocalDateTime finGroupe = null;

            for (int i = 0; i < liste.size(); i++) {
                Evenement e = liste.get(i);
                if (finGroupe != null && !e.getDebut().isBefore(finGroupe)) {
                    for (int j = debutGroupe; j < i; j++) res[j][1] = finsColonnes.size();
                    finsColonnes.clear();
                    debutGroupe = i;
                    finGroupe = null;
                }
                int c = 0;
                while (c < finsColonnes.size() && finsColonnes.get(c).isAfter(e.getDebut())) c++;
                if (c == finsColonnes.size()) finsColonnes.add(e.getFin());
                else finsColonnes.set(c, e.getFin());

                res[i][0] = c;
                if (finGroupe == null || e.getFin().isAfter(finGroupe)) finGroupe = e.getFin();
            }
            for (int j = debutGroupe; j < liste.size(); j++) res[j][1] = finsColonnes.size();
            return res;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            blocs.clear();

            int w = getWidth();
            int h = getHeight();
            int nbHeures = heureFin - heureDebut;
            double largeurJour = (w - MARGE_GAUCHE - 1) / (double) nbJours;
            double hauteurHeure = (h - HAUT_ENTETE - 1) / (double) nbHeures;
            LocalDate aujourdhui = LocalDate.now();

            Font police = Theme.FONT_TABLE.deriveFont(Font.PLAIN, 12f);
            Font gras = police.deriveFont(Font.BOLD);
            g.setFont(police);
            FontMetrics fm = g.getFontMetrics();

            // --- en-tête des jours + colonne du jour courant ---
            g.setColor(ENTETE);
            g.fillRect(0, 0, w, HAUT_ENTETE);
            for (int i = 0; i < nbJours; i++) {
                LocalDate jour = lundi.plusDays(i);
                double x = MARGE_GAUCHE + i * largeurJour;
                boolean estAujourdhui = jour.equals(aujourdhui);

                if (estAujourdhui) {
                    g.setColor(FOND_AUJOURDHUI);
                    g.fill(new Rectangle2D.Double(x, HAUT_ENTETE, largeurJour, h - HAUT_ENTETE));
                }

                String nomJour = majuscule(jour.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRENCH));
                String date = jour.format(DateTimeFormatter.ofPattern("dd/MM"));
                g.setColor(estAujourdhui ? ACCENT : Theme.TEXT_PRIMARY);
                g.setFont(gras);
                g.drawString(nomJour, (float) (x + (largeurJour - g.getFontMetrics().stringWidth(nomJour)) / 2), 19f);
                g.setFont(police);
                g.drawString(date, (float) (x + (largeurJour - fm.stringWidth(date)) / 2), 37f);
            }

            // --- lignes des heures (+ demi-heures en pointillés) et axe des heures ---
            for (int k = 0; k <= nbHeures; k++) {
                double y = HAUT_ENTETE + k * hauteurHeure;
                g.setStroke(TRAIT);
                g.setColor(GRILLE);
                g.draw(new Line2D.Double(MARGE_GAUCHE, y, w, y));

                if (k < nbHeures) {
                    g.setStroke(POINTILLES);
                    g.setColor(GRILLE_LEGERE);
                    double yDemi = y + hauteurHeure / 2;
                    g.draw(new Line2D.Double(MARGE_GAUCHE, yDemi, w, yDemi));

                    String label = String.format("%02d:00", heureDebut + k);
                    g.setColor(TEXTE_DISCRET);
                    g.drawString(label, MARGE_GAUCHE - 8 - fm.stringWidth(label), (float) (y + fm.getAscent() / 2.0));
                }
            }

            // --- séparateurs verticaux entre les jours ---
            g.setStroke(TRAIT);
            g.setColor(GRILLE);
            for (int i = 0; i <= nbJours; i++) {
                double x = MARGE_GAUCHE + i * largeurJour;
                g.draw(new Line2D.Double(x, 0, x, h));
            }

            // --- cours ---
            for (int i = 0; i < nbJours; i++) {
                LocalDate jour = lundi.plusDays(i);
                List<Evenement> duJour = evenementsDuJour(jour);
                int[][] pos = disposer(duJour);
                double xJour = MARGE_GAUCHE + i * largeurJour;

                for (int n = 0; n < duJour.size(); n++) {
                    Evenement e = duJour.get(n);

                    double debutMin = e.getDebut().getHour() * 60 + e.getDebut().getMinute() - heureDebut * 60;
                    double finMin = e.getFin().toLocalDate().equals(jour)
                            ? e.getFin().getHour() * 60 + e.getFin().getMinute() - heureDebut * 60
                            : nbHeures * 60;

                    double y = HAUT_ENTETE + debutMin / 60.0 * hauteurHeure;
                    double hauteur = Math.max(14, (finMin - debutMin) / 60.0 * hauteurHeure);
                    double largeurBloc = largeurJour / pos[n][1];
                    double x = xJour + pos[n][0] * largeurBloc;

                    Rectangle2D.Double zone = new Rectangle2D.Double(x + 1, y + 1, largeurBloc - 3, hauteur - 2);
                    dessinerBloc(g, e, zone, police, gras);
                    blocs.add(new Bloc(zone, e));
                }
            }

            // --- trait rouge de l'heure actuelle ---
            if (aujourdhui.isAfter(lundi.minusDays(1)) && aujourdhui.isBefore(lundi.plusDays(nbJours))) {
                LocalDateTime maintenant = LocalDateTime.now();
                double minutes = maintenant.getHour() * 60 + maintenant.getMinute() - heureDebut * 60;
                if (minutes >= 0 && minutes <= nbHeures * 60) {
                    int i = (int) (aujourdhui.toEpochDay() - lundi.toEpochDay());
                    double x = MARGE_GAUCHE + i * largeurJour;
                    double y = HAUT_ENTETE + minutes / 60.0 * hauteurHeure;
                    g.setColor(TRAIT_MAINTENANT);
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new Line2D.Double(x, y, x + largeurJour, y));
                    g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
                }
            }
            g.dispose();
        }

        private void dessinerBloc(Graphics2D g0, Evenement e, Rectangle2D.Double z, Font police, Font gras) {
            Color fond = couleurs.getOrDefault(e.getTitre(), new Color(0xE0E0E0));

            // Cours écoulé : même teinte, mais grisée (plus sombre en mode sombre, plus pâle en mode clair)
            if (e.getFin().isBefore(LocalDateTime.now())) {
                float[] hsb = Color.RGBtoHSB(fond.getRed(), fond.getGreen(), fond.getBlue(), null);
                fond = Theme.estSombre()
                        ? Color.getHSBColor(hsb[0], hsb[1] * 0.5f, hsb[2] * 0.72f)
                        : Color.getHSBColor(hsb[0], hsb[1] * 0.35f, 0.93f);
            }
            RoundRectangle2D forme = new RoundRectangle2D.Double(z.x, z.y, z.width, z.height, 10, 10);
            g0.setColor(fond);
            g0.fill(forme);
            g0.setStroke(TRAIT);
            g0.setColor(fond.darker());
            g0.draw(forme);

            Graphics2D g = (Graphics2D) g0.create();
            g.clip(forme);
            g.setColor(TEXTE_BLOC);

            float x = (float) z.x + 5;
            float bas = (float) (z.y + z.height);
            int largeurTexte = (int) z.width - 10;

            // titre (gras, 3 lignes max)
            g.setFont(gras);
            FontMetrics fmg = g.getFontMetrics();
            float y = (float) z.y + 3 + fmg.getAscent();
            List<String> lignesTitre = couper(e.getTitre(), fmg, largeurTexte);
            for (int i = 0; i < Math.min(3, lignesTitre.size()); i++) {
                if (y > bas) break;
                g.drawString(lignesTitre.get(i), x, y);
                y += fmg.getHeight();
            }

            // salle puis horaire
            g.setFont(police);
            FontMetrics fm = g.getFontMetrics();
            String salle = (e.getSalle() == null) ? "" : e.getSalle().replace("\n", " ").trim();
            if (!salle.isEmpty() && y <= bas) {
                g.drawString(salle, x, y);
                y += fm.getHeight();
            }
            if (y <= bas) {
                g.setColor(TEXTE_BLOC_DISCRET);
                g.drawString(e.getDebut().format(HEURE) + " - " + e.getFin().format(HEURE), x, y);
            }
            g.dispose();
        }

        private static List<String> couper(String texte, FontMetrics fm, int largeur) {
            List<String> lignes = new ArrayList<>();
            String courante = "";
            for (String mot : texte.trim().split("\\s+")) {
                String essai = courante.isEmpty() ? mot : courante + " " + mot;
                if (courante.isEmpty() || fm.stringWidth(essai) <= largeur) {
                    courante = essai;
                } else {
                    lignes.add(courante);
                    courante = mot;
                }
            }
            if (!courante.isEmpty()) lignes.add(courante);
            return lignes;
        }

        private static String majuscule(String s) {
            return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        private static String echapper(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        /** Infobulle au survol d'un cours : titre complet, jour, horaire, salle. */
        @Override
        public String getToolTipText(MouseEvent ev) {
            for (int i = blocs.size() - 1; i >= 0; i--) {
                Bloc b = blocs.get(i);
                if (b.zone.contains(ev.getPoint())) {
                    Evenement e = b.evenement;
                    String jour = majuscule(e.getDebut().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRENCH))
                            + " " + e.getDebut().format(DateTimeFormatter.ofPattern("dd/MM"));
                    String salle = (e.getSalle() == null || e.getSalle().isBlank()) ? ""
                            : "<br>" + echapper(e.getSalle().replace("\n", " "));
                    return "<html><b>" + echapper(e.getTitre()) + "</b><br>" + jour + "  "
                            + e.getDebut().format(HEURE) + " - " + e.getFin().format(HEURE) + salle + "</html>";
                }
            }
            return null;
        }
    }

    // Fenêtre ouverte (utilisée par le tableau de bord Main.java pour ne pas l'ouvrir deux fois).
    // Lancé seul, fermer la fenêtre quitte l'appli ; ouvert depuis Main, seule la fenêtre se ferme.
    static volatile JFrame fenetre;
    static int fermeture = JFrame.EXIT_ON_CLOSE;

    public static void main(String[] args) {

        Theme.installer();
        Config.demanderSiPremierLancement();

        List<Evenement> evenements;
        Edt edt = null;
        try {
            edt = charger();
            evenements = new ArrayList<>(edt.evenements());
        } catch (IllegalStateException e) {
            JOptionPane.showMessageDialog(null, "Impossible de récupérer l'emploi du temps :\n" + e.getMessage(),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
            evenements = new ArrayList<>();
        }
        evenements.sort(Comparator.comparing(Evenement::getDebut));
        attribuerCouleurs(evenements);

        final List<Evenement> tousLesEvenements = evenements;
        final Edt charge = edt;
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Centrale Étudiant · Emploi du temps");
            frame.setSize(1100, 800);
            frame.setDefaultCloseOperation(fermeture);
            fenetre = frame;
            frame.getContentPane().setBackground(Theme.BACKGROUND);

            VueSemaine vue = new VueSemaine(tousLesEvenements);

            // Pas d'internet : on affiche la dernière sauvegarde et on le signale dans l'en-tête
            JLabel horsLigne = null;
            if (charge != null && charge.horsLigne()) {
                horsLigne = Theme.info(charge.libelleHorsLigne());
                horsLigne.setForeground(Theme.ATTENTION);
                horsLigne.setToolTipText(charge.erreur());
            }
            JPanel entete = Theme.entete("Emploi du temps", horsLigne);

            JLabel semaine = new JLabel(vue.libelleSemaine(), SwingConstants.CENTER);
            semaine.setFont(Theme.FONT_LABEL.deriveFont(Font.BOLD, 15f));
            semaine.setForeground(Theme.TEXT_PRIMARY);
            Runnable maj = () -> semaine.setText(vue.libelleSemaine());

            JButton precedent = Theme.boutonSecondaire("‹");
            JButton aujourdhui = themedButton("Aujourd'hui");
            JButton suivant = Theme.boutonSecondaire("›");
            precedent.addActionListener(e -> { vue.semainePrecedente(); maj.run(); });
            suivant.addActionListener(e -> { vue.semaineSuivante(); maj.run(); });
            aujourdhui.addActionListener(e -> { vue.allerA(jourActuelle()); maj.run(); });

            JPanel boutons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            boutons.setBackground(Theme.BACKGROUND);
            boutons.add(precedent);
            boutons.add(aujourdhui);
            boutons.add(suivant);

            // panneau vide de même largeur que les boutons, pour centrer le titre de la semaine
            JPanel equilibre = new JPanel();
            equilibre.setBackground(Theme.BACKGROUND);
            equilibre.setPreferredSize(boutons.getPreferredSize());

            JPanel barre = new JPanel(new BorderLayout());
            barre.setBackground(Theme.BACKGROUND);
            barre.setBorder(new EmptyBorder(0, 24, 12, 24));
            barre.add(boutons, BorderLayout.WEST);
            barre.add(semaine, BorderLayout.CENTER);
            barre.add(equilibre, BorderLayout.EAST);

            JPanel haut = new JPanel(new BorderLayout());
            haut.setBackground(Theme.BACKGROUND);
            haut.add(entete, BorderLayout.NORTH);
            haut.add(barre, BorderLayout.CENTER);

            JPanel centre = new JPanel(new BorderLayout());
            centre.setBackground(Theme.BACKGROUND);
            centre.setBorder(new EmptyBorder(0, 24, 24, 24));
            centre.add(Theme.encadrer(vue), BorderLayout.CENTER);

            frame.add(haut, BorderLayout.NORTH);
            frame.add(centre, BorderLayout.CENTER);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}