package com.legoj15.megablockbot.platform.neoforge;

//? if neoforge {
import net.neoforged.bus.api.IEventBus;
//? if bot {
/*import com.legoj15.megablockbot.MegaBlockBot;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
*///?}

/**
 * Hands vanilla {@code @GameTest} methods to NeoForge's GameTest registry. NeoForge posts {@code RegisterGameTestsEvent}
 * on the mod bus only when GameTests are enabled, so listening to it is what makes registration a no-op otherwise.
 *
 * <p>The vanilla 1.21.1 registry names a test's structure {@code <namespace>:<class>.<template>}. Dropping the class
 * prefix takes NeoForge's {@code @PrefixGameTestTemplate}, a NeoForge type that common code may not name, so the
 * tests are converted here instead, without the prefix: {@code template = "empty"} is {@code megablockbot:empty}.
 */
final class NeoForgeGameTests {
    private NeoForgeGameTests() {}

    static void register(IEventBus modBus, Class<?>[] holders) {
        //? if bot {
        /*modBus.addListener(RegisterGameTestsEvent.class, event -> addAll(holders));
        *///?}
    }

    //? if bot {
    /*private static void addAll(Class<?>[] holders) {
        // The same filter NeoForge applies to its own registrations (neoforge.enabledGameTestNamespaces, comma list).
        String enabled = System.getProperty("neoforge.enabledGameTestNamespaces");
        Set<String> namespaces = enabled == null ? Set.of()
            : Arrays.stream(enabled.split(",")).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());

        for (Class<?> holder : holders) {
            Arrays.stream(holder.getDeclaredMethods())
                .sorted(Comparator.comparing(Method::getName))
                .filter(method -> method.isAnnotationPresent(GameTest.class))
                .forEach(method -> add(holder, method, namespaces));
        }
    }

    private static void add(Class<?> holder, Method method, Set<String> namespaces) {
        GameTest test = method.getAnnotation(GameTest.class);
        String namespace = test.templateNamespace().isEmpty() ? MegaBlockBot.MOD_ID : test.templateNamespace();
        if (!namespaces.isEmpty() && !namespaces.contains(namespace)) return;

        // Same names as vanilla: "<class>.<method>" lower-cased, unique across holders.
        String name = holder.getSimpleName().toLowerCase() + "." + method.getName().toLowerCase();
        String structure = namespace + ":" + (test.template().isEmpty() ? name : test.template());
        // GameTestRegistry hands out its live lists: this is what its own register(Method) ends up doing.
        GameTestRegistry.getAllTestFunctions().add(new TestFunction(
            test.batch(), name, structure, StructureUtils.getRotationForRotationSteps(test.rotationSteps()),
            test.timeoutTicks(), test.setupTicks(), test.required(), test.manualOnly(),
            test.attempts(), test.requiredSuccesses(), test.skyAccess(), invoker(method)));
        GameTestRegistry.getAllTestClassNames().add(holder.getSimpleName());
        MegaBlockBot.LOGGER.info("Registered GameTest {}", name);
    }

    private static Consumer<GameTestHelper> invoker(Method method) {
        return helper -> {
            try {
                // Static methods only: a test needs no state of its own, and an instance would need a constructor.
                method.invoke(null, helper);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                throw new RuntimeException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        };
    }
    *///?}
}
//?}
