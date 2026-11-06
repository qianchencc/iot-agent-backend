package ltd.qianc.iotagent;

import java.util.regex.Pattern;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * deep module 纪律（对齐 setup-ts-deep-modules 的四条规则）：
 * <ul>
 *   <li>入口边界：模块内部（子包）只许同模块访问，外部只准用包根入口类；</li>
 *   <li>模块内自由：同模块任意深度互访不受限（上一条天然放行）；</li>
 *   <li>测试走入口：测试类不得触碰任何模块的内部；</li>
 *   <li>禁循环：顶层模块之间不得有包循环。</li>
 * </ul>
 * 预留位：分层（模块间准许的依赖方向）待模块数量稳定后补充。
 */
class ArchitectureTest {

    private static final String BASE = "ltd.qianc.iotagent";

    /** 模块 = BASE 直下的一层包；模块子包（更深）中的类皆为内部。 */
    private static final Pattern MODULE_INTERNAL =
            Pattern.compile(Pattern.quote(BASE) + "\\.[a-z][a-z0-9]*\\..+");

    private static final DescribedPredicate<JavaClass> MODULE_INTERNALS =
            new DescribedPredicate<>("模块内部子包中的类") {
                @Override
                public boolean test(JavaClass input) {
                    return MODULE_INTERNAL.matcher(input.getPackageName()).matches();
                }
            };

    @Test
    void moduleInternalsOnlyAccessedWithinOwnModule() {
        classes().that(MODULE_INTERNALS)
                .should(beAccessedOnlyWithinOwnModule())
                .check(importAll());
    }

    @Test
    void testsDependOnlyOnEntryPoints() {
        var testOutput = java.nio.file.Path.of(java.net.URI.create(
                ArchitectureTest.class.getProtectionDomain().getCodeSource().getLocation().toString()));
        noClasses().that(new DescribedPredicate<>("所有测试产物（含辅助类、嵌套类）") {
                    @Override public boolean test(JavaClass type) {
                        return type.getSource().map(source -> java.nio.file.Path.of(source.getUri()).startsWith(testOutput)).orElse(false);
                    }
                })
                .should().dependOnClassesThat(MODULE_INTERNALS)
                .check(importAll());
    }

    @Test
    void publicEntryPointSignaturesDoNotExposeModuleInternals() {
        classes().that().resideInAPackage(BASE + ".*").and().arePublic()
                .should(new ArchCondition<>("公开签名不泄漏 internal 类型") {
                    @Override public void check(JavaClass entry, ConditionEvents events) {
                        entry.getSuperclass().ifPresent(type -> checkExposed(entry.getName(), type, events));
                        entry.getInterfaces().forEach(type -> checkExposed(entry.getName(), type, events));
                        entry.getTypeParameters().forEach(type -> checkExposed(entry.getName(), type, events));
                        var units = new java.util.HashSet<com.tngtech.archunit.core.domain.JavaCodeUnit>(entry.getAllMethods());
                        units.addAll(entry.getConstructors());
                        for (var unit : units) {
                            if (!unit.getModifiers().contains(JavaModifier.PUBLIC)
                                    && !unit.getModifiers().contains(JavaModifier.PROTECTED)) continue;
                            unit.getAllInvolvedRawTypes().forEach(type -> checkExposed(unit.getFullName(), type, events));
                            unit.getExceptionTypes().forEach(type -> checkExposed(unit.getFullName(), type, events));
                            unit.getTypeParameters().forEach(type -> checkExposed(unit.getFullName(), type, events));
                        }
                        for (var field : entry.getAllFields()) {
                            if (field.getModifiers().contains(JavaModifier.PUBLIC)
                                    || field.getModifiers().contains(JavaModifier.PROTECTED)) {
                                checkExposed(field.getFullName(), field.getType(), events);
                            }
                        }
                    }
                }).check(new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE));
    }

    private static void checkExposed(String member, JavaType type, ConditionEvents events) {
        for (JavaClass raw : type.getAllInvolvedRawTypes()) {
            JavaClass target = raw.isArray() ? raw.getBaseComponentType() : raw;
            if (MODULE_INTERNALS.test(target)) {
                events.add(SimpleConditionEvent.violated(type, member + " 暴露 " + target.getName()));
            }
        }
    }

    @Test
    void noPackageCyclesBetweenModules() {
        SlicesRuleDefinition.slices().matching(BASE + ".(*)..")
                .should().beFreeOfCycles()
                .check(importAll());
    }

    private static JavaClasses importAll() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeArchives())
                .importPackages(BASE);
    }

    private static ArchCondition<JavaClass> beAccessedOnlyWithinOwnModule() {
        return new ArchCondition<>("只被同模块（含包根入口）访问") {
            @Override
            public void check(JavaClass internal, ConditionEvents events) {
                String module = moduleOf(internal.getPackageName());
                String modulePackage = BASE + "." + module;
                for (var dependency : internal.getDirectDependenciesToSelf()) {
                    String originPackage = dependency.getOriginClass().getPackageName();
                    boolean sameModule = originPackage.equals(modulePackage)
                            || originPackage.startsWith(modulePackage + ".");
                    if (!sameModule) {
                        events.add(SimpleConditionEvent.violated(dependency,
                                dependency.getOriginClass().getName() + " -> " + internal.getName()
                                        + " 越过模块 " + module + " 的入口边界"));
                    }
                }
            }
        };
    }

    private static String moduleOf(String packageName) {
        return packageName.substring(BASE.length() + 1).split("\\.")[0];
    }
}
