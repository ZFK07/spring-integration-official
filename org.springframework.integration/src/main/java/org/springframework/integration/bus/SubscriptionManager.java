/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.bus;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.Lifecycle;
import org.springframework.integration.ConfigurationException;
import org.springframework.integration.channel.MessageChannel;
import org.springframework.integration.dispatcher.DirectChannel;
import org.springframework.integration.dispatcher.PollingDispatcherTask;
import org.springframework.integration.message.MessageTarget;
import org.springframework.integration.scheduling.TaskScheduler;
import org.springframework.integration.scheduling.PollingSchedule;
import org.springframework.integration.scheduling.Schedule;
import org.springframework.util.Assert;

/**
 * Manages subscriptions for {@link MessageTarget Targets} to a {@link MessageChannel}
 * including the creation, scheduling, and lifecycle management of dispatchers.
 * 
 * @author Mark Fisher
 */
public class SubscriptionManager {

	private final Log logger = LogFactory.getLog(this.getClass());

	private final MessageChannel channel;

	private final TaskScheduler scheduler;

	private volatile Schedule defaultSchedule = new PollingSchedule(5);

	private final ConcurrentMap<Schedule, PollingDispatcherTask> dispatcherTasks = new ConcurrentHashMap<Schedule, PollingDispatcherTask>();

	private final List<Lifecycle> lifecycleTargets = new CopyOnWriteArrayList<Lifecycle>();

	private volatile boolean running;

	private final Object lifecycleMonitor = new Object();


	public SubscriptionManager(MessageChannel channel, TaskScheduler scheduler) {
		Assert.notNull(channel, "channel must not be null");
		Assert.notNull(scheduler, "scheduler must not be null");
		this.channel = channel;
		this.scheduler = scheduler;
	}


	public void setDefaultSchedule(Schedule defaultSchedule) {
		Assert.notNull(defaultSchedule, "'defaultSchedule' must not be null");
		this.defaultSchedule = defaultSchedule;
	}

	public void addTarget(MessageTarget target) {
		this.addTarget(target, null);
	}

	public void addTarget(MessageTarget target, Schedule schedule) {
		Assert.notNull(target, "'target' must not be null");
		if (schedule == null) {
			schedule = this.defaultSchedule;
		}
		else if (this.channel instanceof DirectChannel) {
			if (logger.isInfoEnabled()) {
				logger.info("Subscribing to a DirectChannel. The provided schedule will be ignored.");
			}
		}
		else if (this.channel.getDispatcherPolicy().isPublishSubscribe()) {
			if (logger.isInfoEnabled()) {
				logger.info("This dispatcher broadcasts messages for a publish-subscribe channel. " +
						"Therefore all targets are scheduled with its 'defaultSchedule', " +
						"and the provided schedule will be ignored.");
			}
			schedule = this.defaultSchedule;
		}
		if (target instanceof Lifecycle) {
			this.lifecycleTargets.add((Lifecycle) target);
			if (this.isRunning()) {
				((Lifecycle) target).start();
			}
		}
		if (this.channel instanceof DirectChannel) {
			((DirectChannel) this.channel).subscribe(target);
			return;
		}
		PollingDispatcherTask dispatcherTask = this.dispatcherTasks.get(schedule);
		if (dispatcherTask == null) {
			dispatcherTask = this.dispatcherTasks.putIfAbsent(schedule, new PollingDispatcherTask(this.channel, schedule));
		}
		this.dispatcherTasks.get(schedule).subscribe(target);
		if (dispatcherTask == null && this.isRunning()) {
			this.scheduleDispatcherTask(schedule);
		}
	}

	public boolean removeTarget(MessageTarget target) {
		boolean removed = false;
		Collection<PollingDispatcherTask> dispatcherTaskValues = this.dispatcherTasks.values();
		for (PollingDispatcherTask dispatcherTask : dispatcherTaskValues) {
			removed = (removed || dispatcherTask.unsubscribe(target));
		}
		return removed;
	}

	public boolean isRunning() {
		return this.running;
	}

	public void start() {
		synchronized (this.lifecycleMonitor) {
			if (this.running) {
				return;
			}
			if (this.scheduler == null) {
				throw new ConfigurationException("scheduler is required");
			}
			if (!this.scheduler.isRunning()) {
				this.scheduler.start();
			}
			for (Lifecycle target : lifecycleTargets) {
				target.start();
			}
			for (Schedule schedule : this.dispatcherTasks.keySet()) {
				this.scheduleDispatcherTask(schedule);
			}
			this.running = true;
		}
	}

	private void scheduleDispatcherTask(Schedule schedule) {
		PollingDispatcherTask dispatcherTask = this.dispatcherTasks.get(schedule);
		if (dispatcherTask != null) {
			this.scheduler.schedule(dispatcherTask);
		}
	}

	public void stop() {
		synchronized (this.lifecycleMonitor) {
			if (!this.running) {
				return;
			}
			for (Lifecycle target : lifecycleTargets) {
				target.stop();
			}
			this.running = false;
		}
	}

}
