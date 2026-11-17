package com.agentos.cli.support;

import com.agentos.cli.ProviderNameCatalog;
import java.util.Set;

public record FakeProviderNameCatalog(Set<String> names) implements ProviderNameCatalog {
    public FakeProviderNameCatalog {
        names = Set.copyOf(names);
    }
}
