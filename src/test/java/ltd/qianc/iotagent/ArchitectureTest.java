package ltd.qianc.iotagent;

import java.util.regex.Pattern;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
        noClasses().that(MODULE_INTERNALS)
                .should(beAccessedOnlyWithinOwnModule())
                .check(importAll());
    }

    @Test
    void testsDependOnlyOnEntryPoints() {
        noClasses().that().haveSimpleNameEndingWith("Test")
                .should().dependOnClassesThat(MODULE_INTERNALS)
                .check(importAll());
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
                for (JavaAccess<?> access : internal.getAccessesToSelf()) {
                    String originPackage = access.getOriginOwner().getPackageName();
                    boolean sameModule = originPackage.equals(modulePackage)
                            || originPackage.startsWith(modulePackage + ".");
                    if (!sameModule) {
                        events.add(SimpleConditionEvent.violated(access,
                                access.getOriginOwner().getName() + " -> " + internal.getName()
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
