package com.condertech.protegeela.support;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

@Service
public class PlacesService {
    private final RestClient client;
    private final String endpoint;
    private final RedisPlacesCache sharedCache;
    private record Cached(long expires, Map<String, Object> value) {}
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock gate = new java.util.concurrent.locks.ReentrantLock();
    private long nextRequest;
    private long day;
    private int dailyRequests;

    public PlacesService(@Value("${app.places.overpass-url:https://overpass-api.de/api/interpreter}") String endpoint,
                         RedisPlacesCache sharedCache) {
        this.endpoint = endpoint;
        this.sharedCache = sharedCache;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        client = RestClient.builder().requestFactory(factory)
                .defaultHeader("User-Agent", "ProtegeEla/1.0 (support-point-search)").build();
    }

    // Serialize upstream queries and share a bounded cache across accounts on this server.
    public Map<String, Object> search(PlacesController.SearchInput input) {
        if (!gate.tryLock()) throw new PlacesException(HttpStatus.TOO_MANY_REQUESTS, "Busca ocupada. Tente novamente em instantes.");
        try { return searchLocked(input); } finally { gate.unlock(); }
    }

    private Map<String, Object> searchLocked(PlacesController.SearchInput input) {
        String category = input.category() == null ? "support_center" : input.category();
        boolean nearby = input.latitude() != null;
        String city = input.query() == null ? "" : input.query().trim();
        String scope;
        String prefix = "[out:json][timeout:12][maxsize:16777216];";
        if (nearby) {
            scope = String.format(Locale.ROOT, "(around:%d,%.3f,%.3f)",
                    input.radius() == null ? 10000 : input.radius(), input.latitude(), input.longitude());
        } else {
            prefix += "area[boundary=administrative][name=\"" + escape(city) + "\"]->.city;";
            scope = "(area.city)";
        }
        String filter = switch (category) {
            case "hospital" -> "[amenity=hospital]";
            case "women_police" -> "[amenity=police][name~\"mulher|women|woman|feminin|DEAM|DDM\",i]";
            case "legal" -> "[name~\"defensoria|public defender|legal aid\",i]";
            default -> "[name~\"(centro|casa|apoio|atendimento|refer.ncia).*mulher|women.*(support|centre|center)\",i]";
        };
        // Restrict the candidate set before regex name filters. Global regex scans time out.
        prefix += "nwr" + scope + "[~\"^(amenity|name)$\"~\".\"]->.near;";
        String query = prefix + "nwr.near" + filter + ";out center 100;";
        if (category.equals("all")) {
            query = prefix + "(nwr.near[amenity=hospital];nwr.near[amenity=police]"
                    + ";nwr.near[name~\"defensoria|public defender|legal aid\",i]"
                    + ";nwr.near" + filter + ";);out center 100;";
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expires() <= now);
        Cached hit = cache.get(query);
        if (hit != null) return hit.value();
        Map<String, Object> shared = sharedCache.get(endpoint + query);
        if (shared != null) return shared;
        long today = now / 86400000L;
        if (today != day) { day = today; dailyRequests = 0; }
        if (now < nextRequest || dailyRequests >= 100 || !sharedCache.allowRequest(today)) {
            throw new PlacesException(HttpStatus.TOO_MANY_REQUESTS,
                    "Limite temporario da busca gratuita. Tente novamente mais tarde.");
        }
        nextRequest = now + 5000;
        dailyRequests++;
        try {
            Map<?, ?> response = client.post().uri(endpoint).contentType(MediaType.TEXT_PLAIN)
                    .body(query).retrieve().body(Map.class);
            if (response == null || response.containsKey("remark") || !(response.get("elements") instanceof List<?> elements)) {
                throw new PlacesException(HttpStatus.BAD_GATEWAY, "O servico de locais nao concluiu a busca.");
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object raw : elements) {
                if (!(raw instanceof Map<?, ?> element) || !(element.get("tags") instanceof Map<?, ?> tags)) continue;
                Map<?, ?> point = element.get("center") instanceof Map<?, ?> center ? center : element;
                if (!(point.get("lat") instanceof Number lat) || !(point.get("lon") instanceof Number lon)) continue;
                String type = String.valueOf(element.get("type"));
                String id = String.valueOf(element.get("id"));
                if (!Set.of("node", "way", "relation").contains(type) || !id.matches("[0-9]+")) continue;
                List<String> address = new ArrayList<>();
                for (String field : List.of("addr:street", "addr:housenumber", "addr:suburb", "addr:city", "addr:state")) {
                    if (tags.get(field) instanceof String part && !part.isBlank()) address.add(part);
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "osm:" + type + "/" + id);
                String nameLower = String.valueOf(tags.get("name")).toLowerCase(Locale.ROOT);
                String mappedCategory = "hospital".equals(tags.get("amenity")) ? "health"
                        : "police".equals(tags.get("amenity")) ? "police_station"
                        : nameLower.matches(".*(defensoria|public defender|legal aid).*") ? "legal" : "support_center";
                item.put("category", mappedCategory);
                item.put("city", tags.get("addr:city") instanceof String c ? c : "");
                item.put("state", tags.get("addr:state") instanceof String st ? st : "");
                item.put("is_verified", false);
                item.put("website", "https://www.openstreetmap.org/" + type + "/" + id);
                item.put("name", tags.get("name") instanceof String name ? name : "Hospital");
                item.put("address", address.isEmpty() ? "Endereco nao cadastrado" : String.join(", ", address));
                item.put("latitude", lat.doubleValue());
                item.put("longitude", lon.doubleValue());
                item.put("map_url", "https://www.openstreetmap.org/" + type + "/" + id);
                if (nearby) item.put("distance_m", distance(input.latitude(), input.longitude(), lat.doubleValue(), lon.doubleValue()));
                items.add(item);
            }
            if (nearby) items.sort(Comparator.comparingDouble(item -> ((Number)item.get("distance_m")).doubleValue()));
            Map<String, Object> result = Map.of("items", items.stream().limit(category.equals("all") ? 100 : 20).toList(),
                    "source", "OpenStreetMap", "search_mode", nearby ? "nearby" : "city",
                    "attribution", "OpenStreetMap contributors", "truncated", elements.size() >= 100);
            if (cache.size() >= 100) cache.remove(cache.keySet().iterator().next());
            cache.put(query, new Cached(now + 900000, result));
            sharedCache.put(endpoint + query, result);
            return result;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                nextRequest = System.currentTimeMillis() + 60000;
                throw new PlacesException(HttpStatus.TOO_MANY_REQUESTS, "Servico gratuito ocupado. Tente novamente em um minuto.");
            }
            throw new PlacesException(HttpStatus.BAD_GATEWAY, "Servico de locais indisponivel. Tente mais tarde.");
        } catch (ResourceAccessException exception) {
            throw new PlacesException(HttpStatus.GATEWAY_TIMEOUT, "O servico de locais demorou a responder.");
        } catch (RestClientException exception) {
            throw new PlacesException(HttpStatus.BAD_GATEWAY, "Nao foi possivel consultar os locais.");
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static double distance(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(Math.toRadians(lon2 - lon1) / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
    }
}
