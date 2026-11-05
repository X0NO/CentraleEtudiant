package com.centrale;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.SwingUtilities;

/**
 * Accès commun à la base PostgreSQL (facultative) pour les notes et les devoirs.
 *
 * Pourquoi cette classe : avant, chaque clic (ajouter une note, cocher un devoir...) tentait
 * 2 ou 3 connexions à la BDD directement dans le fil de l'interface. Raspberry Pi injoignable
 * = 3 s d'attente par tentative, l'appli figée 5 à 10 s.
 *
 * Maintenant :
 *  - l'interface n'attend JAMAIS la BDD : elle écrit dans le CSV local (instantané),
 *    puis demande une synchronisation qui tourne sur un fil à part ({@link #planifier}) ;
 *  - délai de connexion court (2 s) ;
 *  - après un échec, la BDD est considérée hors-ligne pendant {@link #PAUSE_APRES_ECHEC_MS} :
 *    les synchronisations suivantes s'arrêtent tout de suite au lieu de réessayer.
 */
final class Bdd {

    private Bdd() {}

    /** Délai max pour ouvrir une connexion (secondes). */
    static final int DELAI_CONNEXION_S = 2;
    /** Après un échec de connexion, on ne réessaie pas avant ce délai. */
    static final long PAUSE_APRES_ECHEC_MS = 30_000;

    enum Etat { LOCAL, INCONNU, EN_LIGNE, HORS_LIGNE }

    private static volatile Etat etat = Etat.INCONNU;
    private static volatile long dernierEchec = 0;
    private static final List<Runnable> ecouteurs = new CopyOnWriteArrayList<>();

    /** Un seul fil pour toutes les synchronisations : jamais deux en même temps, jamais dans l'interface. */
    private static final ExecutorService FIL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "synchro-bdd");
        t.setDaemon(true);
        return t;
    });

    static Etat etat() {
        return Config.bddConfiguree() ? etat : Etat.LOCAL;
    }

    /** Appelé (dans l'interface) à chaque changement d'état : en ligne, hors-ligne... */
    static void auChangement(Runnable r) {
        ecouteurs.add(r);
    }

    private static void changerEtat(Etat nouveau) {
        if (etat == nouveau) return;
        etat = nouveau;
        for (Runnable r : ecouteurs) SwingUtilities.invokeLater(r);
    }

    /** Ouvre une connexion (délai court). Lève une exception si la BDD n'est pas configurée ou injoignable. */
    static Connection connexion() throws SQLException {
        String url = Config.get(Config.DB_URL);
        if (url == null) throw new SQLException("Aucune base de données configurée (mode local)");
        Properties p = new Properties();
        String user = Config.get(Config.DB_USER);
        String mdp = Config.get(Config.DB_PASSWORD);
        if (user != null) p.setProperty("user", user);
        if (mdp != null) p.setProperty("password", mdp);
        if (url.startsWith("jdbc:postgresql:")) {
            p.setProperty("connectTimeout", String.valueOf(DELAI_CONNEXION_S)); // secondes
            p.setProperty("loginTimeout", String.valueOf(DELAI_CONNEXION_S));
            p.setProperty("socketTimeout", "15");
        }
        DriverManager.setLoginTimeout(DELAI_CONNEXION_S);
        try {
            Connection c = DriverManager.getConnection(url, p);
            changerEtat(Etat.EN_LIGNE);
            return c;
        } catch (SQLException e) {
            dernierEchec = System.currentTimeMillis();
            changerEtat(Etat.HORS_LIGNE);
            throw e;
        }
    }

    /**
     * true si la BDD répond. Si elle vient d'échouer, répond false immédiatement
     * (pas de nouvelle tentative avant {@link #PAUSE_APRES_ECHEC_MS}).
     */
    static boolean ping() {
        return ping(false);
    }

    /** @param forcer true = réessaie même si la BDD vient d'échouer (bouton « Synchroniser »). */
    static boolean ping(boolean forcer) {
        if (!Config.bddConfiguree()) return false;
        if (!forcer && etat == Etat.HORS_LIGNE
                && System.currentTimeMillis() - dernierEchec < PAUSE_APRES_ECHEC_MS) {
            return false;
        }
        try (Connection c = connexion()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * false si la BDD n'est pas configurée ou vient d'échouer (on attend avant de réessayer).
     * Ne se connecte pas : sert à éviter une tentative inutile avant {@link #connexion()}.
     */
    static boolean disponible() {
        if (!Config.bddConfiguree()) return false;
        return !(etat == Etat.HORS_LIGNE && System.currentTimeMillis() - dernierEchec < PAUSE_APRES_ECHEC_MS);
    }

    /** Oublie l'échec récent : la prochaine synchronisation réessaiera tout de suite. */
    static void reessayer() {
        dernierEchec = 0;
    }

    /**
     * Lance une synchronisation sur le fil de fond, puis {@code ensuite} dans l'interface (peut être null).
     * Ne bloque jamais l'appelant.
     */
    static void planifier(Runnable synchro, Runnable ensuite) {
        FIL.submit(() -> {
            try {
                synchro.run();
            } catch (Throwable t) {
                System.err.println("Synchronisation impossible : " + t);
            } finally {
                if (ensuite != null) SwingUtilities.invokeLater(ensuite);
            }
        });
    }
}
