package eu.arrowhead.core.serviceregistry.quartz.task;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.test.context.junit4.SpringRunner;

import eu.arrowhead.common.CommonConstants;
import eu.arrowhead.common.database.entity.ServiceDefinition;
import eu.arrowhead.common.database.entity.ServiceRegistry;
import eu.arrowhead.common.database.entity.System;
import eu.arrowhead.core.serviceregistry.database.service.ServiceRegistryDBService;
import eu.arrowhead.common.dto.ServiceSecurityType;

@RunWith (SpringRunner.class)
public class ProviderReachabilityTaskTest {
	
	@InjectMocks
	ProvidersReachabilityTask providersReachabilityTask = new ProvidersReachabilityTask();
	
	@Mock
	ServiceRegistryDBService serviceRegistryDBService;
	
	final ServiceDefinition serviceDefinition = new ServiceDefinition("testService");
	final System testSystem = new System("testSystem", "testAddress*/$", 1, "testAuthenticationInfo");
	
	@Before
	public void setUp() {
		final List<ServiceRegistry> sreviceRegistryEntries = new ArrayList<>();
					
		sreviceRegistryEntries.add(new ServiceRegistry(serviceDefinition, testSystem, "testUri", ZonedDateTime.now(), ServiceSecurityType.TOKEN, "", 1));
			
		final Page<ServiceRegistry> sreviceRegistryEntriesPage = new PageImpl<ServiceRegistry>(sreviceRegistryEntries);
		when(serviceRegistryDBService.getServiceRegistryEntries(anyInt(), anyInt(), eq(Direction.ASC), eq(CommonConstants.COMMON_FIELD_NAME_ID))).thenReturn(sreviceRegistryEntriesPage);
		doNothing().when(serviceRegistryDBService).removeBulkOfServiceRegistryEntries(anyIterable());
	}
	
	@Test
	public void testCheckProvidersReachabilityConnectionFailure() {
		final List<ServiceRegistry> removedEntries = providersReachabilityTask.checkProvidersReachability();
		assertEquals(1, removedEntries.size());
	}
	
}
