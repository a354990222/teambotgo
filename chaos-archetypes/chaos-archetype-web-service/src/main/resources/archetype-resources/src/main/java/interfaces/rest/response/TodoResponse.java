package ${package}.interfaces.rest.response;

import ${package}.application.result.TodoResult;

/**
 * 待办 HTTP 响应。
 *
 * @param id 待办 ID
 * @param title 标题
 * @param completed 是否完成
 */
public record TodoResponse(String id, String title, boolean completed) {

    /**
     * 从应用层视图转换。
     */
    public static TodoResponse from(TodoResult result) {
        return new TodoResponse(result.id(), result.title(), result.completed());
    }
}
