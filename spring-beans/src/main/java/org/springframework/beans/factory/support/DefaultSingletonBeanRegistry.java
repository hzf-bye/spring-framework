/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/** Cache of singleton objects: bean name to bean instance. */
	/**
	 * 存放的是单例 bean 的映射。
	 * 对应关系为 bean name --> bean instance
	 */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/** Cache of singleton factories: bean name to ObjectFactory. */
	/**
	 * 存放的是 ObjectFactory，可以理解为创建单例 bean 的 factory 。
	 * 对应关系是 bean name --> ObjectFactory
	 */
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/** Cache of early singleton objects: bean name to bean instance. */
	/**
	 * 存放的是早期的 bean，对应关系也是 bean name --> bean instance。
	 * 它与 {@link #singletonFactories} 区别在于 earlySingletonObjects 中存放的 bean 不一定是完整。
	 * 不完整的意思就是bean已经创建好了，但是bean属性可能还没初始化
	 * 从 {@link #getSingleton(String)} 方法中，我们可以了解，bean 在创建过程中就已经加入到 earlySingletonObjects 中了。
	 * 所以当在 bean 的创建过程中，就可以通过 getBean() 方法获取。
	 * 这个 Map 也是【循环依赖】的关键所在。
	 */
	private final Map<String, Object> earlySingletonObjects = new HashMap<>(16);

	/** Set of registered singletons, containing the bean names in registration order. */
	/**
	 * 用来保存当前所有已经注册的bean
	 */
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/** Names of beans that are currently in creation. */
	/**
	 * 正在创建中的单例 Bean 的名字的集合
	 */
	private final Set<String> singletonsCurrentlyInCreation =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** Names of beans currently excluded from in creation checks. */
	private final Set<String> inCreationCheckExclusions =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** List of suppressed Exceptions, available for associating related causes. */
	@Nullable
	private Set<Exception> suppressedExceptions;

	/** Flag that indicates whether we're currently within destroySingletons. */
	private boolean singletonsCurrentlyInDestruction = false;

	/** Disposable bean instances: bean name to disposable instance. */
	private final Map<String, Object> disposableBeans = new LinkedHashMap<>();

	/** Map between containing bean names: bean name to Set of bean names that the bean contains. */
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/** Map between dependent bean names: bean name to Set of dependent bean names.
	 * 保存的是依赖 beanName 之间的映射关系：beanName - > 依赖 beanName 的集合
	 * */
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/** Map between depending bean names: bean name to Set of bean names for the bean's dependencies.
	 * 保存的是依赖 beanName 之间的映射关系：依赖 beanName - > beanName 的集合
	 * */
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		synchronized (this.singletonObjects) {
			Object oldObject = this.singletonObjects.get(beanName);
			if (oldObject != null) {
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * Add the given singleton object to the singleton cache of this factory.
	 * <p>To be called for eager registration of singletons.
	 * @param beanName the name of the bean
	 * @param singletonObject the singleton object
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.put(beanName, singletonObject);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.add(beanName);
		}
	}

	/**
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 * @param beanName the name of the bean
	 * @param singletonFactory the factory for the singleton object
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		synchronized (this.singletonObjects) {
			if (!this.singletonObjects.containsKey(beanName)) {
				this.singletonFactories.put(beanName, singletonFactory);
				this.earlySingletonObjects.remove(beanName);
				this.registeredSingletons.add(beanName);
			}
		}
	}

	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		//参数true设置标识允许早期依赖
		return getSingleton(beanName, true);
	}

	/**
	 * Return the (raw) singleton object registered under the given name.
	 * <p>Checks already instantiated singletons and also allows for an early
	 * reference to a currently created singleton (resolving a circular reference).
	 * @param beanName the name of the bean to look for
	 * @param allowEarlyReference whether early references should be created or not
	 * @return the registered singleton object, or {@code null} if none found
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		//检查缓存中是否存在实例
		Object singletonObject = this.singletonObjects.get(beanName);
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			//缓存中的bean为空，且当前bean正在创建，则锁定全部变量并进行处理
			synchronized (this.singletonObjects) {
				// 从 earlySingletonObjects 获取，获取到了说明此bean在创建过程中则不继续处理了
				singletonObject = this.earlySingletonObjects.get(beanName);
				if (singletonObject == null && allowEarlyReference) {
					/*
					 * 当某些方法需要提前初始化的时候则会调用addSingletonFactory方法将对应的
					 * ObjectFactory初始化策略存放在singletonFactories中
					 */
					//从 singletonFactories 中获取对应的 ObjectFactory
					ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
					if (singletonFactory != null) {
						//调用预先设定的getObject方法
						singletonObject = singletonFactory.getObject();
						// 添加 bean 到 earlySingletonObjects 中
						//earlySingletonObjects与singletonFactories互斥
						this.earlySingletonObjects.put(beanName, singletonObject);
						this.singletonFactories.remove(beanName);
					}
				}
			}
		}
		return singletonObject;
	}

	/**
	 * Return the (raw) singleton object registered under the given name,
	 * creating and registering a new one if none registered yet.
	 * @param beanName the name of the bean
	 * @param singletonFactory the ObjectFactory to lazily create the singleton
	 * with, if necessary
	 * @return the registered singleton object
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(beanName, "Bean name must not be null");
		// 全局加锁
		synchronized (this.singletonObjects) {
			// <1> 从缓存中检查一遍
			// 因为 singleton 模式其实就是复用已经创建的 bean 所以这步骤必须检查
			Object singletonObject = this.singletonObjects.get(beanName);
			if (singletonObject == null) {
				//  为空，开始加载过程
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
							"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				// <2> 加载前置处理
				//将当前bean添加至正在创建的bean当中，如果加入失败说明存在循环依赖，则构造器循环依赖报错
				/*
				 * A通过构造器参数B创建A，B通过构造器参数A创建B。即A依赖B,B依赖A
				 * 1.当容器创建A时，查找A是否正在创建，若没有，则准备需要的构造器参数B，并将A放入正在创建的容器中
				 * 2.当容器创建B时，查找B是否正在创建，若没有，则准备需要的构造器参数A，这时候发现A在正在创建的容器中就报错
				 */
				/*
				 * 而为什么单例模式下使用set方法循环依赖spring就可以解决呢？
				 * 还拿上面的情况举例，只是这时是set方法注入
				 * 1.当创建A时，先从缓存(getSingleton(beanName))中取，若取到了则直接返回A，若没有取到则创建A(第一次肯定是直接创建A的)，
				 * 在创建完A后会将A暴露在singletonFactories中，然后再去注入A的属性，此时注入属性B时同样的回去缓存中取，
				 * 如果取到了那么直接返回，否则创建B, 所以若此时2步骤先创建B，那么1中获取B时就是从缓存中取到了2中创建的B，所以得到的都是一个B
				 * 反之亦然。而2中在获取A时也是从缓存中获取到了1中创建的A
				 *
				 *
				 * 所以两种循环依赖核心区别就是，
				 * 构造器循环依赖是在创建bean时必须先行创建依赖的bean，所以就会出现A需要B,B需要A的情况，类似死锁，因此spring只能报错
				 * 而set方法循环依赖的话，spring在创建好对象之后，先将自己暴露出来，放入singletonFactories中，然后在去注入自己需要的bean，
				 * 因此别人可以从缓存中获取到自己，自己也可以从缓冲取到需要注入的bean，而且在将自己暴露在singletonFactories中，与从缓存中取数据，
				 * 以及在创建bean都是锁住同一个对象(singletonObjects)的，这样就解决了循环依赖。
				 *
				 */
				beforeSingletonCreation(beanName);
				boolean newSingleton = false;
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				if (recordSuppressedExceptions) {
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				try {
					// <3> 初始化 bean
					// 这个过程其实是调用 createBean() 方法
					singletonObject = singletonFactory.getObject();
					newSingleton = true;
				}
				catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						throw ex;
					}
				}
				catch (BeanCreationException ex) {
					if (recordSuppressedExceptions) {
						for (Exception suppressedException : this.suppressedExceptions) {
							ex.addRelatedCause(suppressedException);
						}
					}
					throw ex;
				}
				finally {
					if (recordSuppressedExceptions) {
						this.suppressedExceptions = null;
					}
					// <4> 加载bean后的后置处理
					afterSingletonCreation(beanName);
				}
				if (newSingleton) {
					//加入缓存
					addSingleton(beanName, singletonObject);
				}
			}
			return singletonObject;
		}
	}

	/**
	 * Register an Exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * @param ex the Exception to register
	 */
	protected void onSuppressedException(Exception ex) {
		synchronized (this.singletonObjects) {
			if (this.suppressedExceptions != null) {
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * @param beanName the name of the bean
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.remove(beanName);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.remove(beanName);
		}
	}

	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * Return whether the specified singleton bean is currently in creation
	 * (within the entire factory).
	 * @param beanName the name of the bean
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 * 记录加载状态，也就是this.singletonsCurrentlyInCreation.add(beanName)将当前正要创建的bean记录在缓存中，
	 * 这样便可以对循环依赖进行检测
	 */
	protected void beforeSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * Callback after singleton creation.
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 * @param beanName the name of the singleton that has been created
	 * @see #isSingletonCurrentlyInCreation
	 * 当加载bean结束后需要移除缓存中对该bean的正在加载状态的记录
	 */
	protected void afterSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * @param containedBeanName the name of the contained (inner) bean
	 * @param containingBeanName the name of the containing (outer) bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		synchronized (this.containedBeanMap) {
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * @param beanName the name of the bean
	 * @param dependentBeanName the name of the dependent bean
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		// 获取 beanName
		String canonicalName = canonicalName(beanName);

		synchronized (this.dependentBeanMap) {
			// 添加 <canonicalName, <dependentBeanName>> 到 dependentBeanMap 中
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}

		// 添加 <dependentBeanName, <canonicalName>> 到 dependenciesForBeanMap 中
		synchronized (this.dependenciesForBeanMap) {
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * @param beanName the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		// alreadySeen 已经检测的依赖 bean
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		// 获取原始 beanName
		String canonicalName = canonicalName(beanName);
		// 获取当前 beanName 的依赖集合
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		if (dependentBeans == null) {
			return false;
		}
		// 存在，则证明存在已经注册的依赖
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		// 递归检测依赖
		for (String transitiveDependency : dependentBeans) {
			if (alreadySeen == null) {
				alreadySeen = new HashSet<>();
			}
			// 添加到 alreadySeen 中
			alreadySeen.add(beanName);
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		synchronized (this.dependentBeanMap) {
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		if (dependenciesForBean == null) {
			return new String[0];
		}
		synchronized (this.dependenciesForBeanMap) {
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}
		synchronized (this.singletonObjects) {
			this.singletonsCurrentlyInDestruction = true;
		}

		String[] disposableBeanNames;
		synchronized (this.disposableBeans) {
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames[i]);
		}

		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();

		clearSingletonCache();
	}

	/**
	 * Clear all cached singleton instances in this registry.
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		synchronized (this.singletonObjects) {
			this.singletonObjects.clear();
			this.singletonFactories.clear();
			this.earlySingletonObjects.clear();
			this.registeredSingletons.clear();
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Remove a registered singleton of the given name, if any.
		removeSingleton(beanName);

		// Destroy the corresponding DisposableBean instance.
		DisposableBean disposableBean;
		synchronized (this.disposableBeans) {
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		destroyBean(beanName, disposableBean);
	}

	/**
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * @param beanName the name of the bean
	 * @param bean the bean instance to destroy
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// Trigger destruction of dependent beans first...
		Set<String> dependencies;
		synchronized (this.dependentBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			dependencies = this.dependentBeanMap.remove(beanName);
		}
		if (dependencies != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			for (String dependentBeanName : dependencies) {
				destroySingleton(dependentBeanName);
			}
		}

		// Actually destroy the bean now...
		if (bean != null) {
			try {
				bean.destroy();
			}
			catch (Throwable ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// Trigger destruction of contained beans...
		Set<String> containedBeans;
		synchronized (this.containedBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		if (containedBeans != null) {
			for (String containedBeanName : containedBeans) {
				destroySingleton(containedBeanName);
			}
		}

		// Remove destroyed bean from other beans' dependencies.
		synchronized (this.dependentBeanMap) {
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				dependenciesToClean.remove(beanName);
				if (dependenciesToClean.isEmpty()) {
					it.remove();
				}
			}
		}

		// Remove destroyed bean's prepared dependency information.
		this.dependenciesForBeanMap.remove(beanName);
	}

	/**
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 */
	@Override
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
