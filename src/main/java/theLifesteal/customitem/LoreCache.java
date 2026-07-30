package theLifesteal.customitem;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for pre-built lore Component lists.
 *
 * Lore is built once per item ID and reused for all instances.
 * Cache is invalidated when item definitions are modified.
 */
public class LoreCache {

    private final ConcurrentHashMap<String, List<Component>> cache = new ConcurrentHashMap<>();

    /**
     * Get cached lore for an item ID.
     * @return the cached lore list, or null if not cached
     */
    public List<Component> get(String itemId) {
        return cache.get(itemId);
    }

    /**
     * Store lore in the cache for an item ID.
     */
    public void put(String itemId, List<Component> lore) {
        cache.put(itemId, lore);
    }

    /**
     * Remove a specific item's cached lore.
     */
    public void invalidate(String itemId) {
        cache.remove(itemId);
    }

    /**
     * Clear all cached lore (call on reload).
     */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * @return number of cached items
     */
    public int size() {
        return cache.size();
    }
}