package com.tailor.web.jobs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * Real engine job handlers exist only where jobs run: in the worker role, or when
 * {@code app.jobs.run-handlers} is set (the flow tests run them inside an api context and drive
 * them by hand). The api role never runs the engine.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${app.role:api}' == 'worker' or '${app.jobs.run-handlers:false}' == 'true'")
public @interface ConditionalOnJobHandlers {
}
