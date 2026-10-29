package com.prosperr.cas;

import com.prosperr.cas.http.CasServer;
import com.prosperr.cas.store.StatementStore;

public final class CasPortfolioApplication {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4110"));
        System.out.println("cas-portfolio-service on http://localhost:" + port);
        CasServer.create(StatementStore.fromEnvironment()).start(port);
    }
}
