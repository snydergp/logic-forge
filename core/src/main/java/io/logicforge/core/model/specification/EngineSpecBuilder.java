package io.logicforge.core.model.specification;

import io.logicforge.core.annotations.elements.Action;
import io.logicforge.core.annotations.elements.CompoundType;
import io.logicforge.core.annotations.elements.Converter;
import io.logicforge.core.annotations.elements.Function;
import io.logicforge.core.annotations.elements.Property;
import io.logicforge.core.annotations.metadata.Name;
import io.logicforge.core.constant.EngineMethodType;
import io.logicforge.core.engine.Process;
import io.logicforge.core.exception.EngineConfigurationException;
import io.logicforge.core.util.EngineMethodUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class EngineSpecBuilder {

  private static final java.util.function.Function<Class<?>, String> TYPE_ID_NAMING_STRATEGY =
      Class::getName;

  private final Map<String, ProcessSpec> processes = new HashMap<>();
  private final Set<Class<?>> types = new HashSet<>();
  private final Map<Class<?>, Map<String, PropertyInfo>> compoundTypes = new HashMap<>();
  private final Map<String, ActionSpec> actions = new HashMap<>();
  private final Map<String, FunctionSpec> functions = new HashMap<>();
  private final List<ConverterSpec> converters = new ArrayList<>();

  /**
   * Adds methods annotated on the provider object's class to this builder. Only methods annotated with {@link Action},
   * {@link Function}, or {@link Converter} will be processed. Instance methods, and optionally static methods, will be
   * processed. Any instance method calls will be executed against the provided instance.<br>
   * <br>
   *
   * All annotated methods will be checked for validity. An exception will be thrown if errors are found.
   *
   * @param provider the provider instance
   * @return this builder instance
   */
  public EngineSpecBuilder withProviderInstance(final Object provider,
      final boolean processStaticMethods) throws EngineConfigurationException {
    if (provider instanceof Class<?>) {
      throw new IllegalArgumentException(
          "Attempted to pass class instance to withProviderInstance method.");
    }

    final Class<?> providerClass = provider.getClass();
    for (final Method method : providerClass.getMethods()) {
      processMethod(method, provider);
    }
    if (processStaticMethods) {
      withProviderClass(providerClass);
    }

    return this;
  }

  /**
   * Processes are defined from interfaces with a single method. This method will be inspected to generate the process's
   * name, parameters, and return type (if any). When a process is built, it will be returned as an instance of this
   * interface, allowing the process to be called as is if were a normal Java method.
   *
   * @param processClass the interface to use
   * @return this builder
   */
  public EngineSpecBuilder withProcess(final Class<?> processClass)
      throws EngineConfigurationException {
    if (!processClass.isInterface()) {
      throw new IllegalArgumentException("Processes must be defined as Java interfaces");
    }
    final Class<?>[] interfaces = processClass.getInterfaces();
    if (!Arrays.asList(interfaces).contains(Process.class)) {
      throw new IllegalArgumentException(
          "Process interfaces must directly extend %s".formatted(Process.class.getName()));
    }
    final Method[] declaredMethods = processClass.getDeclaredMethods();
    if (declaredMethods.length != 1) {
      throw new IllegalArgumentException("Process interfaces must declare a single method");
    }
    final Method method = declaredMethods[0];
    processProcess(method);
    return this;
  }

  public EngineSpecBuilder withProviderClasses(final Class<?>... providerClasses)
      throws EngineConfigurationException {
    for (final Class<?> providerClass : providerClasses) {
      withProviderClass(providerClass);
    }
    return this;
  }

  public EngineSpecBuilder withProviderClass(final Class<?> providerClass)
      throws EngineConfigurationException {
    for (final Method method : providerClass.getMethods()) {
      processMethod(method, providerClass);
    }
    return this;
  }

  public EngineSpecBuilder withAction(final ActionSpec actionSpec)
      throws EngineConfigurationException {
    actions.put(actionSpec.getName(), actionSpec);
    if (actionSpec.getOutputType() != null) {
      registerType(actionSpec.getOutputType());
    }
    for (final InputSpec input : actionSpec.getInputs()) {
      registerType(input.getType());
    }
    return this;
  }

  public EngineSpecBuilder withFunction(final FunctionSpec functionSpec)
      throws EngineConfigurationException {
    functions.put(functionSpec.getName(), functionSpec);
    registerType(functionSpec.getOutputType());
    for (final InputSpec input : functionSpec.getInputs()) {
      registerType(input.getType());
    }
    return this;
  }

  public EngineSpec build() {
    return new EngineSpec(processes, processTypes(), actions, functions, converters);
  }

  private void processMethod(final Method method, final Object instanceOrClass)
      throws EngineConfigurationException {
    final boolean processStatic = instanceOrClass instanceof Class<?>;
    if (processStatic == isMethodStatic(method)) {
      final Optional<EngineMethodType> optionalType = EngineMethodUtil.analyzeMethod(method);
      if (optionalType.isPresent()) {
        final EngineMethodType type = optionalType.get();
        switch (type) {
          case ACTION -> processAction(method, instanceOrClass);
          case FUNCTION -> processFunction(method, instanceOrClass);
          case CONVERTER -> processConverter(method, instanceOrClass);
        }
      }
    }
  }

  private void registerType(final Class<?> type) throws EngineConfigurationException {
    final Class<?> typeToProcess = type.isArray() ? type.getComponentType() : type;
    types.add(typeToProcess);
    if (type.getAnnotation(CompoundType.class) != null
        && !compoundTypes.containsKey(typeToProcess)) {
      final Map<String, PropertyInfo> propertyInfos = analyzeCompoundType(type);
      compoundTypes.put(typeToProcess, propertyInfos);
      propertyInfos.values().forEach(propertyInfo -> types.add(propertyInfo.getType()));
    }
  }

  /**
   * Processes all types found as actions/functions/converters were added. Processing is deferred until all types are
   * registered because we need to determine relationships between all types in the set.
   *
   * @return a collection of type specifications mapped by their IDs
   */
  private Map<String, TypeSpec> processTypes() {

    /* a mapping from runtime classes to the external type ID used to represent each class */
    final Map<Class<?>, String> typesByClass = types.stream()
        .collect(Collectors.toMap(java.util.function.Function.identity(), TYPE_ID_NAMING_STRATEGY));
    /* a mapping from type ID to a list of IDs for parent types */
    final Map<String, Set<String>> parentTypeIdMappings = buildParentTypeMappings(typesByClass);

    return types.stream().map(type -> {
      final String id = typesByClass.get(type);
      final Set<String> supertypes =
          parentTypeIdMappings.computeIfAbsent(id, (i) -> new HashSet<>());
      final List<String> values;
      if (type.isEnum()) {
        values = Arrays.stream((Enum<?>[]) type.getEnumConstants()).map(Enum::name)
            .collect(Collectors.toList());
      } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
        values = List.of(Boolean.TRUE.toString(), Boolean.FALSE.toString());
      } else {
        values = new ArrayList<>();
      }
      final Map<String, TypePropertySpec> properties = new HashMap<>();
      if (compoundTypes.containsKey(type)) {
        final Map<String, PropertyInfo> propertyInfos = compoundTypes.get(type);
        propertyInfos.values().stream()
            .map(propertyInfo -> new TypePropertySpec(propertyInfo.getName(),
                typesByClass.get(propertyInfo.getType()), propertyInfo.isOptional(),
                propertyInfo.getGetter()

            )).forEach(propertyInfo -> properties.put(propertyInfo.getName(), propertyInfo));

      }
      return new TypeSpec(type, values, new HashSet<>(supertypes), properties);
    }).collect(Collectors.toMap(typeSpec -> typesByClass.get(typeSpec.getRuntimeClass()),
        java.util.function.Function.identity()));
  }


  private Map<String, Set<String>> buildParentTypeMappings(
      final Map<Class<?>, String> typesByClass) {
    final Map<String, Set<String>> parentMapping = new HashMap<>();

    // Find inheritance relationships. Runs in N*N time -- expensive, so should be cached
    for (final Entry<Class<?>, String> subtypeEntry : typesByClass.entrySet()) {
      final Class<?> subtypeClass = subtypeEntry.getKey();
      final String subtypeId = subtypeEntry.getValue();
      for (final Entry<Class<?>, String> supertypeEntry : typesByClass.entrySet()) {
        final Class<?> supertypeClass = supertypeEntry.getKey();
        final String supertypeId = supertypeEntry.getValue();

        if (subtypeId.equals(supertypeId)) {
          continue;
        }

        final Set<String> supertypes =
            parentMapping.computeIfAbsent(subtypeId, (k) -> new HashSet<>());

        if (supertypeClass.isAssignableFrom(subtypeClass)) {
          supertypes.add(supertypeId);
        }
      }
    }

    // Find conversion relationships. To simplify the frontend, we will consider types to which there exists a
    // conversion to be "supertypes", meaning the "input" type can be used in its place
    for (final ConverterSpec converter : converters) {
      final Class<?> inputType = converter.getInputType();
      final Class<?> outputType = converter.getOutputType();

      if (typesByClass.containsKey(inputType) && typesByClass.containsKey(outputType)) {
        final String subtypeId = typesByClass.get(inputType);
        final String supertypeId = typesByClass.get(outputType);
        final Set<String> supertypes =
            parentMapping.computeIfAbsent(subtypeId, (k) -> new HashSet<>());
        supertypes.add(supertypeId);
      }
    }

    return parentMapping;
  }

  private void processProcess(final Method method) throws EngineConfigurationException {
    final String name = getNameForMethod(method);
    final List<InputSpec> inputSpecs = processParameters(method);
    final Class<?> returnType = method.getReturnType();
    final ProcessSpec processSpec = new ProcessSpec(name, method, inputSpecs, returnType);
    processes.put(name, processSpec);
    registerType(returnType);
  }

  private void processAction(final Method method, final Object provider)
      throws EngineConfigurationException {
    final Class<?> returnType = method.getReturnType();
    final String name = getNameForMethod(method);
    final List<InputSpec> inputSpecs = processParameters(method);
    final ActionSpec actionSpec = new ActionSpec(name, method, provider, inputSpecs, returnType);
    actions.put(name, actionSpec);
    registerType(returnType);
  }

  private void processFunction(final Method method, final Object provider)
      throws EngineConfigurationException {
    final Class<?> returnType = method.getReturnType();
    if (Void.class.equals(returnType)) {
      throw new IllegalStateException(
          String.format("Function-annotated method %s has a void return type", method));
    }
    final String name = getNameForMethod(method);
    final List<InputSpec> inputSpecs = processParameters(method);
    final FunctionSpec functionSpec =
        new FunctionSpec(name, method, provider, inputSpecs, returnType);
    functions.put(name, functionSpec);
    registerType(returnType);
  }

  private void processConverter(final Method method, final Object provider)
      throws EngineConfigurationException {
    final Class<?> returnType = method.getReturnType();
    if (Void.class.equals(returnType)) {
      throw new IllegalStateException(
          String.format("Converter-annotated method %s has a void return type", method));
    }
    final List<InputSpec> inputSpecs = processParameters(method);
    if (inputSpecs.size() != 1) {
      throw new IllegalStateException(
          String.format("Converter-annotated method %s must have a single parameter", method));
    }
    final InputSpec inputSpec = inputSpecs.getFirst();
    if (inputSpec.isMulti()) {
      throw new IllegalStateException(
          String.format("Converter-annotated method %s must not use multi-parameters", method));
    }
    final Class<?> inputType = inputSpec.getType();
    converters.add(new ConverterSpec(returnType, inputType, method, provider, inputSpecs));
  }

  private List<InputSpec> processParameters(final Method method)
      throws EngineConfigurationException {
    final List<InputSpec> out = new ArrayList<>();
    for (final Parameter parameter : method.getParameters()) {
      out.add(processParameter(parameter));
    }
    return out;
  }

  private InputSpec processParameter(final Parameter parameter)
      throws EngineConfigurationException {
    final Class<?> parameterType = parameter.getType();
    final String name = getNameForParameter(parameter);
    final boolean multi = parameterType.isArray();
    final Class<?> type = multi ? parameterType.getComponentType() : parameterType;
    registerType(type);
    return new InputSpec(name, type, multi);
  }

  private static MethodHandle getHandleForMethod(final Method method, final Object provider) {
    final Lookup lookup = MethodHandles.lookup();
    final MethodHandle methodHandle;
    try {
      if (isMethodStatic(method) && provider instanceof Class) {
        methodHandle =
            lookup.findStatic((Class<?>) provider, method.getName(), getTypeForMethod(method));
      } else if (!isMethodStatic(method) && !(provider instanceof Class)) {
        final Class<?> providerClass = provider.getClass();
        methodHandle =
            lookup.findVirtual(providerClass, method.getName(), getTypeForMethod(method));
      } else {
        throw new IllegalStateException("Attempted to process method using illegal provider");
      }
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new RuntimeException("Failed to get method handle for method" + method, e);
    }
    return methodHandle;
  }

  private static MethodType getTypeForMethod(final Method method) {
    return MethodType.methodType(method.getReturnType(), method.getParameterTypes());
  }

  private static String getNameForMethod(final Method method) {
    final Name name = method.getAnnotation(Name.class);
    return name != null ? name.value() : method.getName();
  }

  private static boolean isMethodStatic(final Method method) {
    return Modifier.isStatic(method.getModifiers());
  }

  private static String getNameForParameter(final Parameter parameter) {
    final Name name = parameter.getAnnotation(Name.class);
    return name != null ? name.value() : parameter.getName();
  }

  private static Map<String, PropertyInfo> analyzeCompoundType(final Class<?> type)
      throws EngineConfigurationException {
    final Map<String, PropertyInfo> out = new HashMap<>();
    for (final Field declaredField : type.getDeclaredFields()) {
      final PropertyInfo propertyInfo = analyzeCompoundTypeField(declaredField);
      if (propertyInfo != null) {
        out.put(propertyInfo.getName(), propertyInfo);
      }
    }
    return out;
  }

  private static PropertyInfo analyzeCompoundTypeField(final Field field)
      throws EngineConfigurationException {
    final Property propertyAnnotation = field.getAnnotation(Property.class);
    if (propertyAnnotation == null) {
      return null;
    }
    final Name nameAnnotation = field.getAnnotation(Name.class);
    final String name = nameAnnotation != null ? nameAnnotation.value() : field.getName();
    final Class<?> type = field.getType();
    final Class<?> compoundType = field.getDeclaringClass();
    final Method getter = getGetter(compoundType, name, type);
    return new PropertyInfo(name, type, propertyAnnotation.optional(), getter);
  }

  private static Method getGetter(final Class<?> containingClass, final String name,
      final Class<?> type) throws EngineConfigurationException {

    final String beanName = name.substring(0, 1).toUpperCase() + name.substring(1);
    Method getter = null;
    final boolean propertyIsBoolean = type.equals(Boolean.class) || type.equals(boolean.class);
    final List<String> getterNames = new ArrayList<>();
    getterNames.add("get" + beanName);
    if (propertyIsBoolean) {
      getterNames.add("is" + beanName);
    }
    for (final String getterName : getterNames) {
      try {
        getter = containingClass.getMethod(getterName);
      } catch (NoSuchMethodException e) {
        getter = null;
      }
    }
    if (getter == null) {
      throw new EngineConfigurationException(
          ("CompoundType class %s has registered a property %s but no getter is "
              + "present. Valid getters must be public, accept no arguments, return the type indicated by the type ID, "
              + "and be named with the format \"get%s\" or (for boolean values) \"is%s\".")
              .formatted(containingClass, name, beanName, beanName));
    }
    return getter;
  }

  @RequiredArgsConstructor
  @Getter
  private static class PropertyInfo {

    private final String name;
    private final Class<?> type;
    private final boolean optional;
    private final Method getter;

  }



}
