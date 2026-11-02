package com.stove.settlement.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-lifetime HTTP responses only; no payment processing, fee policy, or database. */
public final class TossPayStatementServer implements AutoCloseable {
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,String> responses=new ConcurrentHashMap<>();
    private final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    private final AtomicInteger laterStatus=new AtomicInteger(200);
    private final AtomicInteger contractErrors=new AtomicInteger();
    private final HttpServer server;
    private TossPayStatementServer() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            if(!"POST".equals(exchange.getRequestMethod()) || !"/api/v2/settlement-details".equals(exchange.getRequestURI().getPath())) {
                contractErrors.incrementAndGet();exchange.sendResponseHeaders(405,-1);exchange.close();return;
            }
            JsonNode request=json.readTree(exchange.getRequestBody());requests.add(request);
            String cursor=request.path("nextCursor").asText("");
            byte[] bytes=responses.getOrDefault(cursor,"{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(cursor.isEmpty()?200:laterStatus.get(),bytes.length);
            try(OutputStream out=exchange.getResponseBody()) {out.write(bytes);}
        });
        server.start();
    }
    public static TossPayStatementServer start() throws IOException {return new TossPayStatementServer();}
    public String baseUrl() {return "http://127.0.0.1:"+server.getAddress().getPort();}
    public void respond(String cursor,String body) {responses.put(cursor,body);}
    public String responseAt(String cursor) {return responses.get(cursor);}
    public void laterStatus(int status) {laterStatus.set(status);}
    public List<JsonNode> requests() {return List.copyOf(requests);}
    public int requestCount() {return requests.size();}
    public int contractErrorCount() {return contractErrors.get();}
    public void reset() {responses.clear();requests.clear();contractErrors.set(0);laterStatus.set(200);}
    @Override public void close() {server.stop(0);}
}
