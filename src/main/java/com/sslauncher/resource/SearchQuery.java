package com.sslauncher.resource;

/** One page of a resource search. */
public record SearchQuery(String text, ResourceKind kind, SourceFilter source,
                          String gameVersion, String loader, String sort, int offset, int limit) {
    public SearchQuery {
        text = text == null ? "" : text.trim();
        gameVersion = gameVersion == null ? "" : gameVersion.trim();
        loader = loader == null ? "" : loader.trim();
        sort = sort == null || sort.isBlank() ? "relevance" : sort;
        limit = Math.max(1, Math.min(50, limit));
        offset = Math.max(0, offset);
    }

    public SearchQuery nextPage() {
        return new SearchQuery(text, kind, source, gameVersion, loader, sort,
                offset + limit, limit);
    }
}
