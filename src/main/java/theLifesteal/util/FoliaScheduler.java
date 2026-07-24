package theLifesteal.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Utility class providing a clean scheduler abstraction compatible with Paper and Folia's regional threading.
 */
public class FoliaScheduler {

    public interface TaskHandle {
        void cancel();
        boolean isCancelled();
    }

    private static class ScheduledTaskAdapter implements TaskHandle {
        private final ScheduledTask task;

        public ScheduledTaskAdapter(ScheduledTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            if (task != null) {
                task.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return task != null && task.isCancelled();
        }
    }

    // --- Global Region Scheduler ---

    public static TaskHandle runGlobal(Plugin plugin, Runnable runnable) {
        ScheduledTask task = Bukkit.getGlobalRegionScheduler().run(plugin, t -> runnable.run());
        return new ScheduledTaskAdapter(task);
    }

    public static TaskHandle runGlobalLater(Plugin plugin, Runnable runnable, long delayTicks) {
        long ticks = Math.max(1L, delayTicks);
        ScheduledTask task = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> runnable.run(), ticks);
        return new ScheduledTaskAdapter(task);
    }

    public static TaskHandle runGlobalTimer(Plugin plugin, Consumer<TaskHandle> consumer, long initialDelayTicks, long periodTicks) {
        long delay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        ScheduledTask[] ref = new ScheduledTask[1];
        TaskHandle handle = new TaskHandle() {
            @Override
            public void cancel() {
                if (ref[0] != null) ref[0].cancel();
            }
            @Override
            public boolean isCancelled() {
                return ref[0] != null && ref[0].isCancelled();
            }
        };
        ref[0] = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> consumer.accept(handle), delay, period);
        return handle;
    }

    public static TaskHandle runGlobalTimer(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
        return runGlobalTimer(plugin, task -> runnable.run(), initialDelayTicks, periodTicks);
    }

    // --- Entity Scheduler ---

    public static TaskHandle runEntity(Entity entity, Plugin plugin, Runnable runnable, Runnable retired) {
        if (entity == null || !entity.isValid()) return null;
        ScheduledTask task = entity.getScheduler().run(plugin, t -> runnable.run(), retired);
        return task != null ? new ScheduledTaskAdapter(task) : null;
    }

    public static TaskHandle runEntityLater(Entity entity, Plugin plugin, Runnable runnable, Runnable retired, long delayTicks) {
        if (entity == null || !entity.isValid()) return null;
        long ticks = Math.max(1L, delayTicks);
        ScheduledTask task = entity.getScheduler().runDelayed(plugin, t -> runnable.run(), retired, ticks);
        return task != null ? new ScheduledTaskAdapter(task) : null;
    }

    public static TaskHandle runEntityTimer(Entity entity, Plugin plugin, Consumer<TaskHandle> consumer, Runnable retired, long initialDelayTicks, long periodTicks) {
        if (entity == null || !entity.isValid()) return null;
        long delay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        ScheduledTask[] ref = new ScheduledTask[1];
        TaskHandle handle = new TaskHandle() {
            @Override
            public void cancel() {
                if (ref[0] != null) ref[0].cancel();
            }
            @Override
            public boolean isCancelled() {
                return ref[0] != null && ref[0].isCancelled();
            }
        };
        ref[0] = entity.getScheduler().runAtFixedRate(plugin, t -> consumer.accept(handle), retired, delay, period);
        return handle;
    }

    // Overloads without retired Runnable callback
    public static TaskHandle runEntity(Entity entity, Plugin plugin, Runnable runnable) {
        return runEntity(entity, plugin, runnable, null);
    }

    public static TaskHandle runEntityLater(Entity entity, Plugin plugin, Runnable runnable, long delayTicks) {
        return runEntityLater(entity, plugin, runnable, null, delayTicks);
    }

    public static TaskHandle runEntityTimer(Entity entity, Plugin plugin, Consumer<TaskHandle> consumer, long initialDelayTicks, long periodTicks) {
        return runEntityTimer(entity, plugin, consumer, null, initialDelayTicks, periodTicks);
    }

    // --- Region Scheduler ---

    public static TaskHandle runRegion(Location location, Plugin plugin, Runnable runnable) {
        if (location == null || location.getWorld() == null) return null;
        ScheduledTask task = Bukkit.getRegionScheduler().run(plugin, location, t -> runnable.run());
        return new ScheduledTaskAdapter(task);
    }

    public static TaskHandle runRegionLater(Location location, Plugin plugin, Runnable runnable, long delayTicks) {
        if (location == null || location.getWorld() == null) return null;
        long ticks = Math.max(1L, delayTicks);
        ScheduledTask task = Bukkit.getRegionScheduler().runDelayed(plugin, location, t -> runnable.run(), ticks);
        return new ScheduledTaskAdapter(task);
    }

    public static TaskHandle runRegionTimer(Location location, Plugin plugin, Consumer<TaskHandle> consumer, long initialDelayTicks, long periodTicks) {
        if (location == null || location.getWorld() == null) return null;
        long delay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        ScheduledTask[] ref = new ScheduledTask[1];
        TaskHandle handle = new TaskHandle() {
            @Override
            public void cancel() {
                if (ref[0] != null) ref[0].cancel();
            }
            @Override
            public boolean isCancelled() {
                return ref[0] != null && ref[0].isCancelled();
            }
        };
        ref[0] = Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, t -> consumer.accept(handle), delay, period);
        return handle;
    }

    // --- Async Scheduler ---

    public static TaskHandle runAsync(Plugin plugin, Runnable runnable) {
        ScheduledTask task = Bukkit.getAsyncScheduler().runNow(plugin, t -> runnable.run());
        return new ScheduledTaskAdapter(task);
    }

    public static TaskHandle runAsyncLater(Plugin plugin, Runnable runnable, long delayTicks) {
        long millis = Math.max(1L, delayTicks * 50L);
        ScheduledTask task = Bukkit.getAsyncScheduler().runDelayed(plugin, t -> runnable.run(), millis, TimeUnit.MILLISECONDS);
        return new ScheduledTaskAdapter(task);
    }

    public static TaskHandle runAsyncTimer(Plugin plugin, Consumer<TaskHandle> consumer, long initialDelayTicks, long periodTicks) {
        long delayMillis = Math.max(1L, initialDelayTicks * 50L);
        long periodMillis = Math.max(1L, periodTicks * 50L);
        ScheduledTask[] ref = new ScheduledTask[1];
        TaskHandle handle = new TaskHandle() {
            @Override
            public void cancel() {
                if (ref[0] != null) ref[0].cancel();
            }
            @Override
            public boolean isCancelled() {
                return ref[0] != null && ref[0].isCancelled();
            }
        };
        ref[0] = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> consumer.accept(handle), delayMillis, periodMillis, TimeUnit.MILLISECONDS);
        return handle;
    }
}
