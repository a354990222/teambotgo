package ${package}.interfaces.rest.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建待办请求。
 *
 * @param title 标题
 */
public record CreateTodoRequest(
        @NotBlank(message = "标题不能为空") @Size(max = 100, message = "标题最长 100 个字符") String title
) {
}
