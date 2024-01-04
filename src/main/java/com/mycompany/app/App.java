package com.mycompany.app;
import com.sun.net.httpserver.HttpServer;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicIntegerArray;


public class App {

    private static final String[] SERVER_URIS = {
        "http://35.238.64.216:80/search",
        "http://35.184.181.99:80/search",
        "http://35.184.160.187:80/search"
    };

    public static void main(String[] args) throws IOException {
        int port = 80;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/coordinate", new CoordinationHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Coordinator Server started on port " + port);
    }

    static class CoordinationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                InputStream is = t.getRequestBody();
                String query = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                String responses = sendRequestsToServers(query);
                t.sendResponseHeaders(200, responses.getBytes().length);
                OutputStream os = t.getResponseBody();
                os.write(responses.getBytes());
                os.close();
            }
        }
        private int[] extractPresenceCount(String response) {
            try {
                String presenceCountStr = response.substring(response.lastIndexOf("\"presenceCount\": [") + 18);
                presenceCountStr = presenceCountStr.substring(0, presenceCountStr.indexOf("]"));
                String[] counts = presenceCountStr.split(", ");
                int[] presenceCount = new int[counts.length];
                for (int i = 0; i < counts.length; i++) {
                    presenceCount[i] = Integer.parseInt(counts[i]);
                }
                return presenceCount;
            } catch (Exception e) {
                e.printStackTrace();
                return new int[0]; // Retorna un arreglo vacío en caso de error
            }
        }
        private String sendRequestsToServers(String query) {
            HttpClient client = HttpClient.newBuilder()
                    .version(Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
        
            String[] queryWords = query.split("\\s+");
            AtomicIntegerArray totalPresenceCount = new AtomicIntegerArray(new int[queryWords.length]);
            Map<String, List<Double>> filescores = new HashMap<>();
        
            CompletableFuture<Void>[] futures = new CompletableFuture[3];
            for (int i = 0; i < 3; i++) {
                String requestBody = query + "\n" + (i + 1);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(SERVER_URIS[i]))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
        
                futures[i] = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                   .thenAccept(response -> {
                                       String serverResponse = response.body();
                                       filescores.putAll(processServerResponse(serverResponse));
                                       int[] presenceCount = extractPresenceCount(serverResponse);
                                       for (int j = 0; j < presenceCount.length; j++) {
                                           totalPresenceCount.addAndGet(j, presenceCount[j]);
                                       }
                                   });
            }
        
            CompletableFuture.allOf(futures).join();
        
            int[] totalPresenceCountArray = new int[queryWords.length];
            for (int i = 0; i < totalPresenceCountArray.length; i++) {
                totalPresenceCountArray[i] = totalPresenceCount.get(i);
            }
        
            double[] normalizedPresenceCount = normalizePresenceCount(totalPresenceCountArray);
            Map<String, Double> finalScores = calculateFinalScores(filescores, normalizedPresenceCount);
            return getTop10Files(finalScores);
        }
        
            private String getTop10Files(Map<String, Double> finalScores) {
                    return finalScores.entrySet().stream()
                      .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                      .limit(10)
                      .map(entry -> entry.getKey() + ": " + entry.getValue())
                      .collect(Collectors.joining("\n"));
}

            private Map<String, Double> calculateFinalScores(Map<String, List<Double>> filescores, double[] normalizedPresenceCount) {
                Map<String, Double> finalScores = new HashMap<>();
            
                for (Map.Entry<String, List<Double>> entry : filescores.entrySet()) {
                    String fileName = entry.getKey();
                    List<Double> scores = entry.getValue();
                    double scoreSum = 0.0;
            
                    for (int i = 0; i < scores.size(); i++) {
                        scoreSum += scores.get(i) * normalizedPresenceCount[i];
                    }
            
                    finalScores.put(fileName, scoreSum);
                }
            
                return finalScores;
            }
            
            private double[] normalizePresenceCount(int[] totalPresenceCount) {
                double[] normalizedCount = new double[totalPresenceCount.length];
                for (int i = 0; i < totalPresenceCount.length; i++) {
                    normalizedCount[i] = (double) totalPresenceCount[i] / 47; // Dividir cada elemento por 47
                }
                return normalizedCount;
            }
            
            private int[] sumPresenceCounts(int[] totalPresenceCount, int[] newPresenceCount) {
                if (newPresenceCount.length == totalPresenceCount.length) {
                    for (int i = 0; i < newPresenceCount.length; i++) {
                        totalPresenceCount[i] += newPresenceCount[i];
                    }
                }
                return totalPresenceCount;
            }
            private Map<String, List<Double>> processServerResponse(String serverResponse) {
                Map<String, List<Double>> fileScores = new HashMap<>();
                Pattern pattern = Pattern.compile("\"([^\"]+)\": \\{\"scores\": \\[(.*?)\\]\\}");
                Matcher matcher = pattern.matcher(serverResponse);

                while (matcher.find()) {
                    String fileName = matcher.group(1);
                    String[] scoresStr = matcher.group(2).split(", ");
                    List<Double> scores = new ArrayList<>();
                    for (String scoreStr : scoresStr) {
                        scores.add(Double.parseDouble(scoreStr));
                    }

                    fileScores.put(fileName, scores);
                }

                return fileScores;
            }

    }
}
