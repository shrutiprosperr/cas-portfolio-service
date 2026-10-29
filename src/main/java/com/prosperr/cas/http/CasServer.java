package com.prosperr.cas.http;

import com.prosperr.cas.CasAnalyzer;
import com.prosperr.cas.CasException;
import com.prosperr.cas.store.StatementStore;
import io.javalin.Javalin;
import io.javalin.http.UploadedFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Local HTTP API. It does not log the PDF, the password, or the PAN. */
public final class CasServer {
    private CasServer() {}

    public static Javalin create(StatementStore store) {
        return Javalin.create(config -> config.http.maxRequestSize = 10 * 1024 * 1024L)
                .get("/", ctx -> ctx.html(page()))
                .get("/health", ctx -> ctx.json(Map.of("ok", true)))
                .post("/cas", ctx -> {
                    byte[] bytes;
                    String fileName = "cas.pdf";
                    String password = ctx.header("X-Cas-Password");
                    if (ctx.isMultipartFormData()) {
                        UploadedFile file = ctx.uploadedFile("file");
                        if (file == null) {
                            ctx.status(400).json(Map.of("code", "FILE_REJECTED", "message", "A PDF is required."));
                            return;
                        }
                        bytes = file.content().readAllBytes();
                        if (file.filename() != null && !file.filename().isBlank()) {
                            fileName = file.filename();
                        }
                        if (password == null) {
                            password = ctx.formParam("password");
                        }
                    } else {
                        bytes = ctx.bodyAsBytes();
                        if (ctx.header("X-File-Name") != null) {
                            fileName = ctx.header("X-File-Name");
                        }
                    }
                    if (bytes == null || bytes.length < 5 || !"%PDF".equals(new String(bytes, 0, 4))) {
                        ctx.status(400).json(Map.of("code", "FILE_REJECTED", "message", "Only a PDF can be uploaded."));
                        return;
                    }
                    try {
                        CasAnalyzer.Analysis analysis = CasAnalyzer.analyze(bytes, password, fileName);
                        StatementStore.Stored stored = store.save(analysis);
                        ctx.json(Map.of(
                                "id", stored.id,
                                "statementAsOf", stored.statementAsOf == null ? "" : stored.statementAsOf,
                                "integrity", analysis.portfolio().integrity.status,
                                "portfolio", analysis.portfolio(),
                                "insights", analysis.insights()));
                    } catch (CasException exception) {
                        ctx.status(400).json(Map.of("code", exception.code(), "message", exception.getMessage()));
                    }
                })
                .get("/cas/current", ctx -> {
                    StatementStore.Stored current = store.current();
                    if (current == null) {
                        ctx.status(404).json(Map.of("code", "NOT_FOUND", "message", "No statement has been parsed yet."));
                        return;
                    }
                    ctx.json(current);
                });
    }

    private static String page() {
        try (var page = CasServer.class.getResourceAsStream("/public/index.html")) {
            if (page == null) {
                throw new IllegalStateException("The dashboard page is missing.");
            }
            return new String(page.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("The dashboard page could not be read.", exception);
        }
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4110"));
        create(StatementStore.fromEnvironment()).start(port);
    }
}
