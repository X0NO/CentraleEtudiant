package com.centrale;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Faux Supabase pour les tests : un serveur HTTP local (127.0.0.1, port libre) qui imite ce dont
 * RankingSync a besoin, avec les règles du vrai schéma (tests/reference/app-schema.sql du classement) :
 *
 *  - POST /auth/v1/token?grant_type=password | refresh_token (GoTrue) ;
 *  - /rest/v1/items (PostgREST) : POST (insertion, ou upsert avec on_conflict=user_id,id et
 *    Prefer: resolution=merge-duplicates), PATCH et GET avec les filtres eq. / in.() / is.null ;
 *  - RLS : chaque compte ne voit et ne modifie que ses lignes (user_id = auth.uid()) ;
 *  - contraintes : id UUID, collection dans (grades, homework, settings), data objet, device_id <= 100 car.,
 *    clé primaire (user_id, id), pas de DELETE (suppression logique seulement).
 *
 * Aucune requête ne sort de la machine.
 */
final class FauxSupabase implements AutoCloseable {

    static final Set<String> COLLECTIONS = Set.of("grades", "homework", "settings");
    private static final JsonFactory JSON = new JsonFactory();

    final String cle = "sb_publishable_cle-de-test";
    private final HttpServer serveur;

    private record Compte(String id, String motDePasse) {}
    private record Jeton(String userId, long expireLe) {}

    private final Map<String, Compte> comptes = new HashMap<>();
    private final Map<String, Jeton> jetons = new HashMap<>();
    private final Map<String, String> rafraichissements = new HashMap<>();
    /** Lignes de la table items (toutes les colonnes). */
    final List<Map<String, Object>> items = new ArrayList<>();

    // pilotage par les tests
    volatile boolean horsLigne = false;      // coupe la connexion sans répondre (comme un réseau absent)
    volatile boolean erreurServeur = false;  // répond 503
    volatile int dureeJetonS = 3600;

    // compteurs
    int connexions, rafraichis, requetesItems, ecritures, lignesRecues, coupures;
    /** Tout ce que le serveur a reçu (pour vérifier qu'un secret ne part pas là où il ne doit pas). */
    final List<String> journal = new ArrayList<>();

    FauxSupabase() throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", this::traiter);
        serveur.start();
    }

    String url() {
        return "http://127.0.0.1:" + serveur.getAddress().getPort();
    }

    /** Crée un compte et renvoie son identifiant (auth.users.id). */
    synchronized String creerCompte(String email, String motDePasse) {
        String id = UUID.randomUUID().toString();
        comptes.put(email.toLowerCase(), new Compte(id, motDePasse));
        return id;
    }

    synchronized void expirerJetons() {
        for (Map.Entry<String, Jeton> e : jetons.entrySet()) e.setValue(new Jeton(e.getValue().userId(), 0));
    }

    /** Ligne ajoutée directement en base (ex : note saisie dans le gestionnaire web). */
    synchronized void inserer(String userId, String id, String collection, Map<String, Object> data, String appareil) {
        String maintenant = Instant.now().toString();
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("user_id", userId);
        l.put("id", id);
        l.put("collection", collection);
        l.put("data", data);
        l.put("deleted_at", null);
        l.put("client_updated_at", maintenant);
        l.put("device_id", appareil);
        l.put("created_at", maintenant);
        l.put("updated_at", maintenant);
        items.add(l);
    }

    synchronized List<Map<String, Object>> lignes(String userId) {
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> l : items) if (userId.equals(l.get("user_id"))) res.add(new LinkedHashMap<>(l));
        return res;
    }

    synchronized Map<String, Object> ligne(String userId, String id) {
        for (Map<String, Object> l : items) {
            if (userId.equals(l.get("user_id")) && id.equals(l.get("id"))) return new LinkedHashMap<>(l);
        }
        return null;
    }

    /** Notes vivantes d'un compte (collection grades, deleted_at null). */
    synchronized List<Map<String, Object>> notesVivantes(String userId) {
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> l : lignes(userId)) {
            if ("grades".equals(l.get("collection")) && l.get("deleted_at") == null) res.add(l);
        }
        return res;
    }

    @Override public void close() {
        serveur.stop(0);
    }

    // =====================================================================
    // Traitement des requêtes
    // =====================================================================
    private record Rep(int statut, String corps) {}

    private static Rep erreur(int statut, String code, String message) {
        return new Rep(statut, "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private void traiter(HttpExchange x) throws IOException {
        try {
            if (horsLigne) {
                synchronized (this) { coupures++; }
                x.close();
                return;
            }
            String corps = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Rep r;
            synchronized (this) {
                journal.add(x.getRequestMethod() + " " + x.getRequestURI() + " " + x.getRequestHeaders().entrySet() + " " + corps);
                r = erreurServeur ? erreur(503, "503", "Service Unavailable") : repondre(x, corps);
            }
            byte[] octets = r.corps() == null ? new byte[0] : r.corps().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (octets.length == 0) {
                x.sendResponseHeaders(r.statut(), -1);
            } else {
                x.sendResponseHeaders(r.statut(), octets.length);
                try (OutputStream o = x.getResponseBody()) {
                    o.write(octets);
                }
            }
        } finally {
            x.close();
        }
    }

    private Rep repondre(HttpExchange x, String corps) {
        String chemin = x.getRequestURI().getPath();
        Map<String, String> q = requete(x.getRequestURI().getRawQuery());
        if (!cle.equals(x.getRequestHeaders().getFirst("apikey"))) {
            return new Rep(401, "{\"message\":\"Invalid API key\"}");
        }
        if (chemin.equals("/auth/v1/token") && x.getRequestMethod().equals("POST")) return jeton(q, corps);
        if (chemin.equals("/rest/v1/items")) {
            requetesItems++;
            String auth = x.getRequestHeaders().getFirst("Authorization");
            Jeton j = (auth != null && auth.startsWith("Bearer ")) ? jetons.get(auth.substring(7)) : null;
            if (j == null || j.expireLe() < System.currentTimeMillis()) return erreur(401, "PGRST301", "JWT expired");
            String prefer = String.valueOf(x.getRequestHeaders().getFirst("Prefer"));
            return switch (x.getRequestMethod()) {
                case "POST" -> inserer(j.userId(), q, prefer, corps);
                case "PATCH" -> modifier(j.userId(), q, corps);
                case "GET" -> lire(j.userId(), q);
                // aucun droit DELETE sur la table : suppression logique uniquement
                default -> erreur(403, "42501", "permission denied for table items");
            };
        }
        return erreur(404, "PGRST205", "not found");
    }

    // ---------- GoTrue ----------
    private Rep jeton(Map<String, String> q, String corps) {
        Map<String, Object> o = RankingSync.Json.objet(corps);
        String userId;
        if ("password".equals(q.get("grant_type"))) {
            connexions++;
            Compte c = comptes.get(String.valueOf(o.get("email")).toLowerCase());
            if (c == null || !c.motDePasse().equals(o.get("password"))) {
                return new Rep(400, "{\"code\":400,\"error_code\":\"invalid_credentials\",\"msg\":\"Invalid login credentials\"}");
            }
            userId = c.id();
        } else if ("refresh_token".equals(q.get("grant_type"))) {
            rafraichis++;
            userId = rafraichissements.remove(String.valueOf(o.get("refresh_token"))); // usage unique
            if (userId == null) {
                return new Rep(400, "{\"code\":400,\"error_code\":\"refresh_token_not_found\",\"msg\":\"Invalid Refresh Token\"}");
            }
        } else {
            return new Rep(400, "{\"code\":400,\"error_code\":\"invalid_request\",\"msg\":\"unsupported_grant_type\"}");
        }
        String acces = "acces-" + UUID.randomUUID();
        String rafraichissement = "rafraichissement-" + UUID.randomUUID();
        jetons.put(acces, new Jeton(userId, System.currentTimeMillis() + dureeJetonS * 1000L));
        rafraichissements.put(rafraichissement, userId);
        return new Rep(200, "{\"access_token\":\"" + acces + "\",\"token_type\":\"bearer\",\"expires_in\":" + dureeJetonS
                + ",\"refresh_token\":\"" + rafraichissement + "\",\"user\":{\"id\":\"" + userId + "\"}}");
    }

    // ---------- PostgREST : POST (insertion / upsert), tout ou rien ----------
    @SuppressWarnings("unchecked")
    private Rep inserer(String uid, Map<String, String> q, String prefer, String corps) {
        ecritures++;
        Object json = RankingSync.Json.lire(corps);
        List<Object> lignes = json instanceof List<?> l ? (List<Object>) l : json instanceof Map<?, ?> ? List.of(json) : null;
        if (lignes == null) return erreur(400, "PGRST102", "Empty or invalid json");
        boolean fusion = prefer.contains("resolution=merge-duplicates") && "user_id,id".equals(q.get("on_conflict"));

        List<Map<String, Object>> copie = new ArrayList<>();
        for (Map<String, Object> l : items) copie.add(new LinkedHashMap<>(l));
        String maintenant = Instant.now().toString();

        for (Object o : lignes) {
            if (!(o instanceof Map<?, ?> brut)) return erreur(400, "PGRST102", "invalid row");
            Map<String, Object> l = (Map<String, Object>) brut;
            for (String col : l.keySet()) {
                if (!Set.of("user_id", "id", "collection", "data", "deleted_at", "client_updated_at", "device_id").contains(col)) {
                    return erreur(400, "PGRST204", "Could not find the column " + col);
                }
            }
            String userId = l.containsKey("user_id") ? String.valueOf(l.get("user_id")) : uid; // défaut : auth.uid()
            String id = String.valueOf(l.get("id"));
            if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                return erreur(400, "22P02", "invalid input syntax for type uuid");
            }
            id = id.toLowerCase();
            if (!userId.equals(uid)) return erreur(403, "42501", "new row violates row-level security policy for table items");
            if (!(l.get("collection") instanceof String c) || !COLLECTIONS.contains(c)) return erreur(400, "23514", "items_collection_check");
            if (!(l.get("data") instanceof Map)) return erreur(400, "23514", "items_data_object_check");
            if (l.get("device_id") instanceof String d && d.length() > 100) return erreur(400, "23514", "items_device_id_check");

            Map<String, Object> existante = null;
            for (Map<String, Object> e : copie) if (uid.equals(e.get("user_id")) && id.equals(e.get("id"))) existante = e;
            if (existante != null && !fusion) return erreur(409, "23505", "duplicate key value violates unique constraint items_pkey");
            if (existante == null) {
                existante = new LinkedHashMap<>();
                existante.put("user_id", uid);
                existante.put("id", id);
                existante.put("deleted_at", null);
                existante.put("device_id", null);
                existante.put("created_at", maintenant);
                copie.add(existante);
            }
            for (String col : new String[]{"collection", "data", "deleted_at", "client_updated_at", "device_id"}) {
                if (l.containsKey(col)) existante.put(col, l.get(col));
            }
            existante.putIfAbsent("client_updated_at", maintenant);
            existante.put("updated_at", maintenant); // horloge du serveur (trigger)
            lignesRecues++;
        }
        items.clear();
        items.addAll(copie);
        return new Rep(201, null);
    }

    // ---------- PostgREST : PATCH ----------
    private Rep modifier(String uid, Map<String, String> q, String corps) {
        ecritures++;
        Map<String, Object> patch = RankingSync.Json.objet(corps);
        for (String col : patch.keySet()) {
            if (!Set.of("collection", "data", "deleted_at", "client_updated_at", "device_id").contains(col)) {
                return erreur(400, "PGRST204", "Could not find the column " + col);
            }
        }
        if (patch.get("device_id") instanceof String d && d.length() > 100) return erreur(400, "23514", "items_device_id_check");
        for (Map<String, Object> l : items) {
            if (!uid.equals(l.get("user_id")) || !correspond(l, q)) continue; // RLS : seulement ses propres lignes
            l.putAll(patch);
            l.put("updated_at", Instant.now().toString());
        }
        return new Rep(204, null);
    }

    // ---------- PostgREST : GET ----------
    private Rep lire(String uid, Map<String, String> q) {
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map<String, Object> l : items) if (uid.equals(l.get("user_id")) && correspond(l, q)) res.add(l);
        res.sort(Comparator.comparing(l -> String.valueOf(l.get("id"))));
        int debut = q.containsKey("offset") ? Integer.parseInt(q.get("offset")) : 0;
        int limite = q.containsKey("limit") ? Integer.parseInt(q.get("limit")) : Integer.MAX_VALUE;
        List<String> colonnes = q.containsKey("select") ? Arrays.asList(q.get("select").split(",")) : null;
        List<Object> page = new ArrayList<>();
        for (int i = debut; i < res.size() && page.size() < limite; i++) {
            Map<String, Object> l = new LinkedHashMap<>(res.get(i));
            if (colonnes != null) l.keySet().retainAll(colonnes);
            page.add(l);
        }
        return new Rep(200, json(page));
    }

    /** Filtres PostgREST : colonne=eq.valeur, colonne=in.(a,b), colonne=is.null. */
    private static boolean correspond(Map<String, Object> ligne, Map<String, String> q) {
        for (Map.Entry<String, String> f : q.entrySet()) {
            String col = f.getKey(), v = f.getValue();
            if (Set.of("select", "order", "limit", "offset", "on_conflict").contains(col)) continue;
            Object valeur = ligne.get(col);
            if (v.startsWith("eq.")) {
                if (!v.substring(3).equals(String.valueOf(valeur))) return false;
            } else if (v.startsWith("in.(") && v.endsWith(")")) {
                if (!Arrays.asList(v.substring(4, v.length() - 1).split(",")).contains(String.valueOf(valeur))) return false;
            } else if (v.equals("is.null")) {
                if (valeur != null) return false;
            } else {
                throw new IllegalArgumentException("filtre non géré par le faux serveur : " + col + "=" + v);
            }
        }
        return true;
    }

    private static Map<String, String> requete(String brute) {
        Map<String, String> q = new LinkedHashMap<>();
        if (brute == null) return q;
        for (String p : brute.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) q.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    // ---------- écriture JSON ----------
    static String json(Object o) {
        try {
            StringWriter w = new StringWriter();
            try (JsonGenerator g = JSON.createGenerator(w)) {
                ecrire(g, o);
            }
            return w.toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void ecrire(JsonGenerator g, Object o) throws IOException {
        if (o == null) g.writeNull();
        else if (o instanceof Map<?, ?> m) {
            g.writeStartObject();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                g.writeFieldName(String.valueOf(e.getKey()));
                ecrire(g, e.getValue());
            }
            g.writeEndObject();
        } else if (o instanceof List<?> l) {
            g.writeStartArray();
            for (Object v : l) ecrire(g, v);
            g.writeEndArray();
        } else if (o instanceof Long n) g.writeNumber(n);
        else if (o instanceof Integer n) g.writeNumber(n);
        else if (o instanceof Double n) g.writeNumber(n);
        else if (o instanceof Boolean b) g.writeBoolean(b);
        else g.writeString(String.valueOf(o));
    }
}
