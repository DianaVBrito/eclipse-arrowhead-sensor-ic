package eu.arrowhead.core.qos.quartz.task;

import java.security.PublicKey;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Resource;

import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponents;

import eu.arrowhead.common.CommonConstants;
import eu.arrowhead.common.CoreCommonConstants;
import eu.arrowhead.common.SSLProperties;
import eu.arrowhead.common.core.CoreSystemService;
import eu.arrowhead.common.database.entity.QoSInterRelayMeasurement;
import eu.arrowhead.common.dto.internal.CloudAccessListResponseDTO;
import eu.arrowhead.common.dto.internal.CloudAccessResponseDTO;
import eu.arrowhead.common.dto.internal.CloudResponseDTO;
import eu.arrowhead.common.dto.internal.CloudWithRelaysAndPublicRelaysListResponseDTO;
import eu.arrowhead.common.dto.internal.CloudWithRelaysAndPublicRelaysResponseDTO;
import eu.arrowhead.common.dto.internal.DTOConverter;
import eu.arrowhead.common.dto.internal.QoSRelayTestProposalRequestDTO;
import eu.arrowhead.common.dto.internal.RelayResponseDTO;
import eu.arrowhead.common.dto.shared.CloudRequestDTO;
import eu.arrowhead.common.dto.shared.QoSMeasurementStatus;
import eu.arrowhead.common.dto.shared.QoSMeasurementType;
import eu.arrowhead.common.exception.ArrowheadException;
import eu.arrowhead.core.qos.database.service.QoSDBService;
import eu.arrowhead.core.qos.service.QoSMonitorDriver;

@Component
@DisallowConcurrentExecution
public class RelayEchoTask implements Job {
	
	//=================================================================================================
	// members
	
	@Autowired
	private QoSMonitorDriver qosMonitorDriver;
	
	@Autowired
	private QoSDBService qosDBService;
	
	@Autowired
	protected SSLProperties sslProperties;

	@Resource(name = CommonConstants.ARROWHEAD_CONTEXT)
	private Map<String,Object> arrowheadContext;
	
	@Resource(name = "relayEchoScheduler")
	private Scheduler relayEchoScheduler;
	
	@Value(CoreCommonConstants.$QOS_ENABLED_RELAY_TASK_WD)
	private boolean relayTaskEnabled;
	
	@Value(CoreCommonConstants.$RELAY_TEST_BAD_GATEWAY_RETRY_MIN_WD)
	private int badGatewayRetryMin;
	
	private static final List<CoreSystemService> REQUIRED_CORE_SERVICES = List.of(CoreSystemService.GATEKEEPER_PULL_CLOUDS, CoreSystemService.GATEKEEPER_COLLECT_ACCESS_TYPES,
			  																	  CoreSystemService.GATEKEEPER_COLLECT_SYSTEM_ADDRESSES, CoreSystemService.GATEKEEPER_RELAY_TEST_SERVICE);
	
	private static final Map<String, ZonedDateTime> BAD_GATEWAY_CACHE = new HashMap<>();
	private ZonedDateTime taskStartedAt;
	private ZonedDateTime latestMeasurementTimeKnown;
	
	private final Logger logger = LogManager.getLogger(RelayEchoTask.class);
	
	private final String CLOUD_HAS_NO_GATEKEEPER_RELAY_WARNING_MESSAGE = "The following cloud do not have GATEKEEPER relay: ";
	private final String CLOUD_HAS_NO_GATEWAY_OR_PUBLIC_RELAY_WARNING_MESSAGE = "The following cloud do not have GATEWAY or PUBLIC relay: ";

	//=================================================================================================
	// methods
	
	//-------------------------------------------------------------------------------------------------
	@Override
	public void execute(final JobExecutionContext context) throws JobExecutionException {
		logger.debug("STARTED: Relay Echo task");
		taskStartedAt = ZonedDateTime.now();
		
		if (!relayTaskEnabled) {
			logger.debug("FINISHED: Relay Echo task disabled");
			shutdown();
			return;
		}
		
		if (arrowheadContext.containsKey(CoreCommonConstants.SERVER_STANDALONE_MODE)) {
			logger.debug("FINISHED: Relay Echo task can not run if server is in standalon mode");
			shutdown();
			return;
		}
		
		if (!sslProperties.isSslEnabled()) {
			logger.debug("FINISHED: Relay Echo task can not run if server is not in secure mode");
			shutdown();
			return;
		}		
		
		if (!checkRequiredCoreSystemServiceUrisAvailable()) {
			logger.debug("FINISHED: Relay Echo task. Reqired Core System Sevice URIs aren't available");
			return;
		}
		
		QoSRelayTestProposalRequestDTO testProposal = null;
		try {
			testProposal = findCloudRelayPairToTest();
			if (testProposal.getTargetCloud() == null || testProposal.getRelay() == null) {
				logger.debug("FINISHED: Relay Echo task. Have no cloud-relay pair to run relay echo test");
				return;
			}
			
			qosMonitorDriver.requestGatekeeperInitRelayTest(testProposal);			
			logger.debug("FINISHED: Relay Echo task success");
			
		} catch (final ArrowheadException ex) {
			logger.debug("FAILED: Relay Echo task: " + ex.getMessage());
			if (testProposal != null && ex.getErrorCode() == HttpStatus.SC_BAD_GATEWAY) {
				final String relayCacheKey = getRelayCacheKey(testProposal.getTargetCloud().getOperator(), testProposal.getTargetCloud().getName(),
						   									  testProposal.getRelay().getAddress(), testProposal.getRelay().getPort());
				BAD_GATEWAY_CACHE.put(relayCacheKey, latestMeasurementTimeKnown);
			}
		}
	}

	//=================================================================================================
	// assistant methods
	
	//-------------------------------------------------------------------------------------------------
	private void shutdown() {
		logger.debug("shutdown started...");
		try {
			relayEchoScheduler.shutdown();
			logger.debug("SHUTDOWN: Relay Echo task");
		} catch (final SchedulerException ex) {
			logger.error(ex.getMessage());
			logger.debug("Stacktrace:", ex);
		}
	}
	
	//-------------------------------------------------------------------------------------------------
	@SuppressWarnings("unchecked")
	private boolean checkRequiredCoreSystemServiceUrisAvailable() {
		logger.debug("checkRequiredCoreSystemServiceUrisAvailable started...");
		for (final CoreSystemService coreSystemService : REQUIRED_CORE_SERVICES) {
			final String key = coreSystemService.getServiceDefinition() + CoreCommonConstants.URI_SUFFIX;
			if (!arrowheadContext.containsKey(key) && !(arrowheadContext.get(key) instanceof UriComponents)) {
				return false;
			}
		}
		return true;
	}
	
	//-------------------------------------------------------------------------------------------------
	private QoSRelayTestProposalRequestDTO findCloudRelayPairToTest() {
		logger.debug("selectCloudRelayPairToTest started...");
		final QoSRelayTestProposalRequestDTO proposal = new QoSRelayTestProposalRequestDTO();
		
		if (!arrowheadContext.containsKey(CommonConstants.SERVER_PUBLIC_KEY)) {
			throw new ArrowheadException("Public key is not available.");
		}		
		final PublicKey publicKey = (PublicKey) arrowheadContext.get(CommonConstants.SERVER_PUBLIC_KEY);		
		proposal.setSenderQoSMonitorPublicKey(Base64.getEncoder().encodeToString(publicKey.getEncoded()));		
		
		final CloudWithRelaysAndPublicRelaysListResponseDTO allCloud = qosMonitorDriver.queryGatekeeperAllCloud();
		proposal.setRequesterCloud(getOwnCloud(allCloud.getData()));
		final List<CloudWithRelaysAndPublicRelaysResponseDTO> cloudsWithoutDirectAccess = filterOnCloudsWithoutDirectAccess(allCloud.getData());
		
		latestMeasurementTimeKnown = ZonedDateTime.now().plusHours(1);
		for (final CloudWithRelaysAndPublicRelaysResponseDTO cloud : cloudsWithoutDirectAccess) {
			if (cloud.getGatekeeperRelays() == null && cloud.getGatekeeperRelays().isEmpty()) {
				logger.info(CLOUD_HAS_NO_GATEKEEPER_RELAY_WARNING_MESSAGE + cloud.getName() + "." + cloud.getOperator());
			} else {
				if(cloud.getGatewayRelays() != null && !cloud.getGatewayRelays().isEmpty()) {
					final QoSMeasurementStatus status = selectRelayFromCloudToTest(cloud, cloud.getGatewayRelays(), proposal);
					if (status != null && status == QoSMeasurementStatus.NEW) {
						return proposal;
					}
				} else {
					if (cloud.getPublicRelays() == null || cloud.getPublicRelays().isEmpty()) {
						logger.info(CLOUD_HAS_NO_GATEWAY_OR_PUBLIC_RELAY_WARNING_MESSAGE + cloud.getName() + "." + cloud.getOperator());
					} else {
						final QoSMeasurementStatus status = selectRelayFromCloudToTest(cloud, cloud.getPublicRelays(), proposal);
						if (status != null && status == QoSMeasurementStatus.NEW) {
							return proposal;
						}
					}
				}
			}
		}

		removeCloudRelayPairsFromBadGatewayCacheIfNotTestable(cloudsWithoutDirectAccess);		
		if (proposal.getRelay() == null) {
			findCloudRelayPairToTestFromBadGatewayCache(cloudsWithoutDirectAccess, proposal);
		}	
		
		return proposal;
	}
	
	//-------------------------------------------------------------------------------------------------
	private List<CloudWithRelaysAndPublicRelaysResponseDTO> filterOnCloudsWithoutDirectAccess(final List<CloudWithRelaysAndPublicRelaysResponseDTO> clouds) {
		logger.debug("filterOnCloudsWithoutDirectAccess started...");
		
		final List<CloudRequestDTO> cloudsToRequest = new ArrayList<>();
		for (final CloudWithRelaysAndPublicRelaysResponseDTO cloud : clouds) {
			cloudsToRequest.add(DTOConverter.convertCloudResponseDTOToCloudRequestDTO(cloud));
		}
		
		final CloudAccessListResponseDTO cloudsWithAccessTypes = qosMonitorDriver.queryGatekeeperCloudAccessTypes(cloudsToRequest);
		
		final List<CloudWithRelaysAndPublicRelaysResponseDTO> cloudsWithoutDirectAccess = new ArrayList<>();
		for (final CloudWithRelaysAndPublicRelaysResponseDTO cloud : clouds) {
			if (!cloud.getOwnCloud()) {
				for (final CloudAccessResponseDTO cloudAccess : cloudsWithAccessTypes.getData()) {
					if (!cloudAccess.isDirectAccess() && cloudAccess.getCloudOperator().equalsIgnoreCase(cloud.getOperator()) && cloudAccess.getCloudName().equalsIgnoreCase(cloud.getName())) {
						cloudsWithoutDirectAccess.add(cloud);
					}
				}
			}
		}
		
		return cloudsWithoutDirectAccess;
	}
	
	//-------------------------------------------------------------------------------------------------
	private CloudRequestDTO getOwnCloud(final List<CloudWithRelaysAndPublicRelaysResponseDTO> clouds) {
		logger.debug("getOwnCloud started...");
		
		for (final CloudWithRelaysAndPublicRelaysResponseDTO cloud : clouds) {
			if (cloud.getOwnCloud() && cloud.getSecure()) {
				return DTOConverter.convertCloudResponseDTOToCloudRequestDTO(cloud);
			}
		}
		
		throw new ArrowheadException("Secure own cloud was not found.");
	}
	
	//-------------------------------------------------------------------------------------------------
	private QoSMeasurementStatus selectRelayFromCloudToTest(final CloudResponseDTO cloud, final List<RelayResponseDTO> relayList, final QoSRelayTestProposalRequestDTO proposal) {
		logger.debug("selectRelayFromCloudToTest started...");
		
		QoSMeasurementStatus statusOfSelected = null;
		if (relayList != null && !relayList.isEmpty()) {
			for (final RelayResponseDTO relay : relayList) {
				final Optional<QoSInterRelayMeasurement> measurementOpt = qosDBService.getInterRelayMeasurement(cloud, relay, QoSMeasurementType.RELAY_ECHO);
				if (measurementOpt.isEmpty() || measurementOpt.get().getStatus() == QoSMeasurementStatus.NEW) {
					proposal.setTargetCloud(DTOConverter.convertCloudResponseDTOToCloudRequestDTO(cloud));
					proposal.setRelay(DTOConverter.convertRelayResponseDTOToRelayRequestDTO(relay));
					return QoSMeasurementStatus.NEW;
					
				} else if (measurementOpt.isPresent() && measurementOpt.get().getStatus() != QoSMeasurementStatus.PENDING) {
					final QoSInterRelayMeasurement echoMeasurement = measurementOpt.get();
					if (echoMeasurement.getLastMeasurementAt().isBefore(latestMeasurementTimeKnown) && !isRelayLockedFromTesting(cloud, relay, echoMeasurement.getLastMeasurementAt())) {
						proposal.setTargetCloud(DTOConverter.convertCloudResponseDTOToCloudRequestDTO(cloud));
						proposal.setRelay(DTOConverter.convertRelayResponseDTOToRelayRequestDTO(relay));
						latestMeasurementTimeKnown = echoMeasurement.getLastMeasurementAt();
						statusOfSelected = measurementOpt.get().getStatus();
					}
				}
			}
		}
		return statusOfSelected;
	}
	
	//-------------------------------------------------------------------------------------------------
	private boolean isRelayLockedFromTesting(final CloudResponseDTO cloud, final RelayResponseDTO relay, final ZonedDateTime lastMeasurementAt) {
		final String cacheKey = getRelayCacheKey(cloud.getOperator(), cloud.getName(), relay.getAddress(), relay.getPort());
		if (!BAD_GATEWAY_CACHE.containsKey(cacheKey)) {
			return false;
		} else if (lastMeasurementAt.plusMinutes(badGatewayRetryMin).isBefore(taskStartedAt)) {
			BAD_GATEWAY_CACHE.remove(cacheKey);
			return false;
		} else {
			return true;
		}
	}
	
	//-------------------------------------------------------------------------------------------------
	private QoSRelayTestProposalRequestDTO findCloudRelayPairToTestFromBadGatewayCache(final List<CloudWithRelaysAndPublicRelaysResponseDTO> cloudsWithoutDirectAccess,
																					   final QoSRelayTestProposalRequestDTO proposal) {
		if (cloudsWithoutDirectAccess != null && !cloudsWithoutDirectAccess.isEmpty()) {
			String latestRelayCaheKey = "";
			ZonedDateTime latestMeasurmentTimeInRelayCache = ZonedDateTime.now().plusHours(1);
			for (final Entry<String, ZonedDateTime> entry : BAD_GATEWAY_CACHE.entrySet()) {
				if (entry.getValue().isBefore(latestMeasurmentTimeInRelayCache)) {
					latestRelayCaheKey = entry.getKey();
					latestMeasurmentTimeInRelayCache = entry.getValue();
				}
			}
			
			for (final CloudWithRelaysAndPublicRelaysResponseDTO cloud : cloudsWithoutDirectAccess) {
				final String cloudKey = latestRelayCaheKey.split("-")[0];
				if (cloudKey.equalsIgnoreCase(cloud.getOperator() + "." + cloud.getName())) {
					if (cloud.getGatekeeperRelays() == null && cloud.getGatekeeperRelays().isEmpty()) {
						logger.info(CLOUD_HAS_NO_GATEKEEPER_RELAY_WARNING_MESSAGE + cloud.getName() + "." + cloud.getOperator());
					} else {
						for (final RelayResponseDTO gwRelay : cloud.getGatewayRelays()) {
							if (latestRelayCaheKey.equalsIgnoreCase(getRelayCacheKey(cloud.getOperator(), cloud.getName(), gwRelay.getAddress(), gwRelay.getPort()))) {
								proposal.setTargetCloud(DTOConverter.convertCloudResponseDTOToCloudRequestDTO(cloud));
								proposal.setRelay(DTOConverter.convertRelayResponseDTOToRelayRequestDTO(gwRelay));
								return proposal;
							}
						}
						for (final RelayResponseDTO pRelay : cloud.getPublicRelays()) {
							if (latestRelayCaheKey.equalsIgnoreCase(getRelayCacheKey(cloud.getOperator(), cloud.getName(), pRelay.getAddress(), pRelay.getPort()))) {
								proposal.setTargetCloud(DTOConverter.convertCloudResponseDTOToCloudRequestDTO(cloud));
								proposal.setRelay(DTOConverter.convertRelayResponseDTOToRelayRequestDTO(pRelay));
								return proposal;
							}
						}
					}					
				}
			}
		}
		return proposal;
	}
	
	//-------------------------------------------------------------------------------------------------
	private void removeCloudRelayPairsFromBadGatewayCacheIfNotTestable(final List<CloudWithRelaysAndPublicRelaysResponseDTO> cloudsWithoutDirectAccess) {
		final Set<String> toBeRemoved = new HashSet<>();
		
		for (final Entry<String, ZonedDateTime> entry : BAD_GATEWAY_CACHE.entrySet()) {
			final String relayCacheKey = entry.getKey();
			boolean stillExist = false;
			
			for (final CloudWithRelaysAndPublicRelaysResponseDTO cloud : cloudsWithoutDirectAccess) {				
				if (!cloud.getOwnCloud()) {
					if (cloud.getGatekeeperRelays() == null && cloud.getGatekeeperRelays().isEmpty()) {
						logger.info(CLOUD_HAS_NO_GATEKEEPER_RELAY_WARNING_MESSAGE + cloud.getName() + "." + cloud.getOperator());
					}					
					if (cloud.getGatewayRelays() != null && !cloud.getGatewayRelays().isEmpty()) {
						for (final RelayResponseDTO gwRelay : cloud.getGatewayRelays()) {
							if (relayCacheKey.equalsIgnoreCase(getRelayCacheKey(cloud.getOperator(), cloud.getName(), gwRelay.getAddress(), gwRelay.getPort()))) {
								stillExist = true;
								break;
							}
						}						
					}					
					if (!stillExist && cloud.getPublicRelays() != null && !cloud.getPublicRelays().isEmpty()) {
						for (final RelayResponseDTO pRelay : cloud.getPublicRelays()) {
							if (relayCacheKey.equalsIgnoreCase(getRelayCacheKey(cloud.getOperator(), cloud.getName(), pRelay.getAddress(), pRelay.getPort()))) {
								stillExist = true;
								break;
							}
						}						
					}
				}
			}
			
			if (!stillExist) {
				toBeRemoved.add(relayCacheKey);
			}
		}
		
		for (final String key : toBeRemoved) {
			BAD_GATEWAY_CACHE.remove(key);
		}
	}
	
	//-------------------------------------------------------------------------------------------------
	private String getRelayCacheKey(final String cloudOperator, final String cloudName, final String relayAddress, final int relayPort) {
		return cloudOperator + "." + cloudName + "-" + relayAddress + ":" + relayPort;
	}
}
