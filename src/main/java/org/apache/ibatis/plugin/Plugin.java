/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {

  private final Object target;
  private final Interceptor interceptor;
  private final Map<Class<?>, Set<Method>> signatureMap;

  private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
    this.target = target;
    this.interceptor = interceptor;
    this.signatureMap = signatureMap;
  }

  //pluginAll方法为目标对象生成代理，之后目标对象调用方法的时候走的不是原方法而是代理方法，
  public static Object wrap(Object target, Interceptor interceptor) {
    //获取Interceptor上定义的所有方法签名：
    Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
    Class<?> type = target.getClass();
    //传入的是Target的Class对象以及之前获取到的方法签名映射：
    //这里获取Target的所有接口，如果方法签名映射中有这个接口，那么添加到interfaces中，这是一个Set，最终将Set转换为数组返回。
    Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
    if (interfaces.length > 0) {
      //如果当前传入的Target的接口中有@Intercepts注解中定义的接口，那么为之生成代理，否则原Target返回。
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          interfaces,
          new Plugin(target, interceptor, signatureMap));
    }
    return target;
  }

  //形参Object target，这个是Executor、ParameterHandler、ResultSetHandler、StatementHandler接口的实现类，换句话说，plugin方法是要为Executor、ParameterHandler、ResultSetHandler、StatementHandler的实现类生成代理，从而在调用这几个类的方法的时候，其实调用的是InvocationHandler的invoke方法
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      //，将method对应的Class拿出来，获取该Class中有哪些方法签名，换句话说就是Executor、ParameterHandler、ResultSetHandler、StatementHandler，在@Intercepts注解中定义了要拦截哪些方法签名。
      Set<Method> methods = signatureMap.get(method.getDeclaringClass());
      if (methods != null && methods.contains(method)) {
       //如果当前调用的方法的方法签名在方法签名集合中，即满足第4行的判断，那么调用拦截器的intercept方法，否则方法原样调用
        return interceptor.intercept(new Invocation(target, method, args));
      }
      return method.invoke(target, args);
    } catch (Exception e) {
      throw ExceptionUtil.unwrapThrowable(e);
    }
  }

  private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
    //看到先拿@Intercepts注解，如果没有定义@Intercepts注解，抛出异常，这意味着使用MyBatis的插件，必须使用注解方式。
    Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
    // issue #251
    if (interceptsAnnotation == null) {
      throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());      
    }
    //拿到@Intercepts注解下的所有@Signature注解
    Signature[] sigs = interceptsAnnotation.value();
    Map<Class<?>, Set<Method>> signatureMap = new HashMap<Class<?>, Set<Method>>();
    for (Signature sig : sigs) {
      //获取其type属性（表示具体某个接口）
      Set<Method> methods = signatureMap.get(sig.type());
      if (methods == null) {
        methods = new HashSet<Method>();
        signatureMap.put(sig.type(), methods);
      }
      try {
        //再根据method与args两个属性去type下找方法签名一致的方法Method（如果没有方法签名一致的就抛出异常，此签名的方法在该接口下找不到），能找到的话key=type，value=Set<Method>，添加到signatureMap中，构建出一个方法签名映射。
        Method method = sig.type().getMethod(sig.method(), sig.args());
        methods.add(method);
      } catch (NoSuchMethodException e) {
        throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
      }
    }
    return signatureMap;
  }

  private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
    Set<Class<?>> interfaces = new HashSet<Class<?>>();
    while (type != null) {
      for (Class<?> c : type.getInterfaces()) {
        if (signatureMap.containsKey(c)) {
          interfaces.add(c);
        }
      }
      type = type.getSuperclass();
    }
    return interfaces.toArray(new Class<?>[interfaces.size()]);
  }

}
