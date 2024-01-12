package io.logicforge.core.builtin.functions;

import io.logicforge.core.annotations.Function;
import io.logicforge.core.injectable.ExecutionContext;

public class VariableFunctions {

  @Function
  public static Boolean isSet(final String name, final ExecutionContext context) {
    return context.contains(name);
  }

  @Function
  public static Object get(final String name, final ExecutionContext context) {
    return context.get(name);
  }
}
