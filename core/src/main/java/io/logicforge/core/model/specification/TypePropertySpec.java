package io.logicforge.core.model.specification;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;
import java.util.Optional;

@Data
@Builder
@RequiredArgsConstructor
public class TypePropertySpec {

  private final String name;

  private final String typeId;

  private final boolean optional;

  private final Method getter;

}
