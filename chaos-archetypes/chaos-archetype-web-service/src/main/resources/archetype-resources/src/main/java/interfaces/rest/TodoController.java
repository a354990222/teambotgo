package ${package}.interfaces.rest;

import ${package}.application.TodoApplicationService;
import ${package}.application.command.CreateTodoCommand;
import ${package}.interfaces.rest.request.CreateTodoRequest;
import ${package}.interfaces.rest.response.TodoResponse;
import com.chaos.web.idempotent.Idempotent;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 待办 HTTP 接口。
 *
 * <p>返回值会被 chaos-web 自动包装为统一响应 {@code {code, message, data, traceId}}，
 * 异常由全局异常处理器转换为对应错误码，这里只需返回业务对象。</p>
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoApplicationService todoApplicationService;

    public TodoController(TodoApplicationService todoApplicationService) {
        this.todoApplicationService = todoApplicationService;
    }

    @GetMapping
    public List<TodoResponse> list() {
        return todoApplicationService.list().stream().map(TodoResponse::from).toList();
    }

    /**
     * 创建待办。{@code @Idempotent} 要求请求携带 {@code Idempotency-Key} 头，防止客户端重试造成重复创建。
     */
    @PostMapping
    @Idempotent
    public TodoResponse create(@Valid @RequestBody CreateTodoRequest request) {
        return TodoResponse.from(todoApplicationService.create(new CreateTodoCommand(request.title())));
    }

    @PostMapping("/{id}/complete")
    public TodoResponse complete(@PathVariable String id) {
        return TodoResponse.from(todoApplicationService.complete(id));
    }
}
