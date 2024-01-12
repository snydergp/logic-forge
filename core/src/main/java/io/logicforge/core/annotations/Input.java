package io.logicforge.core.annotations;

import io.logicforge.core.constant.WellKnownValues;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Input {

  String name() default WellKnownValues.USE_PARAMETER_NAME;

  Property[] properties() default {};

}
