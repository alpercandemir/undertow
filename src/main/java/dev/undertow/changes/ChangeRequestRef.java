package dev.undertow.changes;

import java.net.URI;

public record ChangeRequestRef(
        String provider,
        URI instance,
        String repository,
        int number,
        URI url,
        String stableRepositoryId) {
    public ChangeRequestRef(String provider, URI instance, String repository, int number, URI url) {
        this(provider, instance, repository, number, url, repository);
    }

    public ChangeRequestRef {
        if (!provider.matches("[a-z][a-z0-9_-]{0,30}")
                || number < 1
                || number > 100000000
                || !repository.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
                || repository.contains("..")
                || repository.startsWith(".")
                || !stableRepositoryId.matches("[A-Za-z0-9_./:-]{1,200}")
                || !"https".equals(instance.getScheme())
                || instance.getUserInfo() != null
                || instance.getPort() != -1
                || instance.getQuery() != null
                || instance.getFragment() != null
                || instance.getHost() == null
                || !(instance.getPath().isEmpty() || instance.getPath().equals("/"))
                || !instance.getHost().equals(url.getHost())
                || !"https".equals(url.getScheme())
                || url.getUserInfo() != null
                || url.getPort() != -1)
            throw new IllegalArgumentException("Invalid change request identity or instance");
        if (provider.equals("github")
                && (!instance.getHost().equals("github.com")
                        || !url.getPath().equals("/" + repository + "/pull/" + number)))
            throw new IllegalArgumentException(
                    "Only GitHub.com is supported by the GitHub adapter");
    }
}
