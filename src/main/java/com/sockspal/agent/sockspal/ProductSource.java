package com.sockspal.agent.sockspal;

import java.util.List;

/** Where the catalog comes from: the live API, or a fixture in tests. */
@FunctionalInterface
public interface ProductSource {

    List<Product> wholesaleProducts();
}
