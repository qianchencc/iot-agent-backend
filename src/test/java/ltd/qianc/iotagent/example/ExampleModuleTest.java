package ltd.qianc.iotagent.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;

/** 只 import 入口类；模块 internal 不可触碰（ArchitectureTest 强制）。 */
class ExampleModuleTest {

    private final GreetingFacade facade = new GreetingFacade();

    @Test
    void greetsAfterTrimming() {
        assertThat(facade.greet("  世界 ")).isEqualTo("你好，世界");
    }

    @Test
    void rejectsBlankNameWithBusinessError() {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> facade.greet("   "));
        assertThat(e.code()).isEqualTo("EMPTY_NAME");
        assertThat(e.getMessage()).isEqualTo("称呼不能为空");
    }
}
