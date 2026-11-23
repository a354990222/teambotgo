package ${package}.application.result;

import ${package}.domain.todo.Todo;

/**
 * 待办应用层视图。
 *
 * @param id 待办 ID
 * @param title 标题
 * @param completed 是否完成
 */
public record TodoResult(String id, String title, boolean completed) {

    /**
     * 从聚合根转换。
     */
    public static TodoResult from(Todo todo) {
        return new TodoResult(todo.id(), todo.title(), todo.completed());
    }
}
