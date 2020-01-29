package eu.arrowhead.core.qos.quartz.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import eu.arrowhead.common.CoreCommonConstants;
import eu.arrowhead.common.quartz.AutoWiringSpringBeanQuartzTaskFactory;

@Configuration
public class CountRestarterTaskConfig {

	//=================================================================================================
	// members

	protected Logger logger = LogManager.getLogger(CountRestarterTaskConfig.class);

	@Autowired
	private ApplicationContext applicationContext; //NOSONAR
	
	@Value(CoreCommonConstants.$PING_TTL_SCHEDULED_WD)
	private boolean ttlScheduled;
	
	@Value(CoreCommonConstants.$PING_TTL_INTERVAL_WD)
	private int ttlInterval;
	
	private static final int SCHEDULER_DELAY = 7;
	private static final String CRONE_EXPRESSION_MIDNIGHT_EVERY_DAY= "0 0 0 ? * * *";
	
	private static final String NAME_OF_TRIGGER = "Counter_Restart_Task_Trigger";
	private static final String NAME_OF_TASK = "Counter_Restart_Task_Detail";
	
	//=================================================================================================
	// methods

	//-------------------------------------------------------------------------------------------------
	@Bean
	public SchedulerFactoryBean counterRestarterTaskSheduler() {
		final AutoWiringSpringBeanQuartzTaskFactory jobFactory = new AutoWiringSpringBeanQuartzTaskFactory();
		jobFactory.setApplicationContext(applicationContext);
		
		final SchedulerFactoryBean schedulerFactory = new SchedulerFactoryBean();
		if (ttlScheduled) {			
			schedulerFactory.setJobFactory(jobFactory);
			schedulerFactory.setJobDetails(counterRestartTaskDetails().getObject());
			schedulerFactory.setTriggers(counterRestartTaskTrigger().getObject());
			schedulerFactory.setStartupDelay(SCHEDULER_DELAY);
			logger.info("Pin task adjusted with ttl interval: {} minutes", ttlInterval);
		} else {
			logger.info("Ping task is not adjusted");
		}

		return schedulerFactory;
	}
	
	//-------------------------------------------------------------------------------------------------
	@Bean
	public CronTriggerFactoryBean counterRestartTaskTrigger() {
		
		CronTriggerFactoryBean trigger = new CronTriggerFactoryBean();
		trigger.setJobDetail(counterRestartTaskDetails().getObject());
		trigger.setCronExpression(CRONE_EXPRESSION_MIDNIGHT_EVERY_DAY);
		trigger.setName(NAME_OF_TRIGGER);

		return trigger;
	}

	//-------------------------------------------------------------------------------------------------
	@Bean
	public JobDetailFactoryBean counterRestartTaskDetails() {
		final JobDetailFactoryBean jobDetailFactory = new JobDetailFactoryBean();
		jobDetailFactory.setJobClass(CountRestarterTask.class);
		jobDetailFactory.setName(NAME_OF_TASK);
		jobDetailFactory.setDurability(true);

		return jobDetailFactory;
	}
}