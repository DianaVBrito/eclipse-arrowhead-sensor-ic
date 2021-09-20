/********************************************************************************
 * Copyright (c) 2020 AITIA
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   AITIA - implementation
 *   Arrowhead Consortia - conceptualization
 ********************************************************************************/

package eu.arrowhead.core.choreographer;

import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import eu.arrowhead.common.CommonConstants;
import eu.arrowhead.common.CoreCommonConstants;
import eu.arrowhead.common.CoreDefaults;
import eu.arrowhead.common.CoreUtilities;
import eu.arrowhead.common.CoreUtilities.ValidatedPageParams;
import eu.arrowhead.common.Defaults;
import eu.arrowhead.common.Utilities;
import eu.arrowhead.common.database.entity.ChoreographerSession;
import eu.arrowhead.common.dto.internal.ChoreographerStartSessionDTO;
import eu.arrowhead.common.dto.shared.ChoreographerCheckPlanResponseDTO;
import eu.arrowhead.common.dto.shared.ChoreographerPlanListResponseDTO;
import eu.arrowhead.common.dto.shared.ChoreographerPlanRequestDTO;
import eu.arrowhead.common.dto.shared.ChoreographerPlanResponseDTO;
import eu.arrowhead.common.dto.shared.ChoreographerRunPlanRequestDTO;
import eu.arrowhead.common.dto.shared.ChoreographerRunPlanResponseDTO;
import eu.arrowhead.common.dto.shared.ErrorMessageDTO;
import eu.arrowhead.common.exception.BadPayloadException;
import eu.arrowhead.core.choreographer.database.service.ChoreographerPlanDBService;
import eu.arrowhead.core.choreographer.database.service.ChoreographerSessionDBService;
import eu.arrowhead.core.choreographer.service.ChoreographerService;
import eu.arrowhead.core.choreographer.validation.ChoreographerPlanExecutionChecker;
import eu.arrowhead.core.choreographer.validation.ChoreographerPlanValidator;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

@Api(tags = { CoreCommonConstants.SWAGGER_TAG_ALL })
@CrossOrigin(maxAge = Defaults.CORS_MAX_AGE, allowCredentials = Defaults.CORS_ALLOW_CREDENTIALS,
			 allowedHeaders = { HttpHeaders.ORIGIN, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, HttpHeaders.AUTHORIZATION }
)
@RestController
@RequestMapping(CommonConstants.CHOREOGRAPHER_URI)
public class ChoreographerPlanController {

	//=================================================================================================
	// members

	private static final String FALSE = "false";
    private static final String REQUEST_PARRAM_ALLOW_INTER_CLOUD = "allowInterCloud";
    private static final String PATH_VARIABLE_ID = "id";
    private static final String ID_NOT_VALID_ERROR_MESSAGE = "ID must be greater than 0.";

    private static final String PLAN_MGMT_URI = CoreCommonConstants.MGMT_URI + "/plan";
    private static final String PLAN_MGMT_BY_ID_URI = PLAN_MGMT_URI + "/{" + PATH_VARIABLE_ID + "}";
    private static final String SESSION_MGMT_URI = CoreCommonConstants.MGMT_URI + "/session";
    private static final String START_SESSION_MGMT_URI = SESSION_MGMT_URI + "/start";
    private static final String CHECK_PLAN_MGMT_BY_ID_URI = CoreCommonConstants.MGMT_URI + "/check-plan/{" + PATH_VARIABLE_ID + "}";

    private static final String GET_PlAN_MGMT_HTTP_200_MESSAGE = "Plan returned.";
    private static final String GET_PLAN_MGMT_HTTP_400_MESSAGE = "Could not retrieve plan.";

    private static final String GET_CHECK_PLAN_MGMT_HTTP_200_MESSAGE = "Check report returned.";
    private static final String GET_CHECK_PLAN_MGMT_HTTP_400_MESSAGE = "Could not retrieve check report.";

    private static final String POST_PLAN_MGMT_HTTP_201_MESSAGE = "Plan created with given service definition and first Action.";
    private static final String POST_PLAN_MGMT_HTTP_400_MESSAGE = "Could not create Plan.";

    private static final String DELETE_PLAN_HTTP_200_MESSAGE = "Plan successfully removed.";
    private static final String DELETE_PLAN_HTTP_400_MESSAGE = "Could not remove Plan.";

    private static final String START_SESSION_HTTP_200_MESSAGE = "Initiated plan execution with given id(s).";
    private static final String START_PLAN_HTTP_400_MESSAGE = "Could not start plan with given id(s).";

    private final Logger logger = LogManager.getLogger(ChoreographerPlanController.class);

    @Autowired
    private ChoreographerPlanDBService planDBService;
    
    @Autowired
    private ChoreographerSessionDBService sessionDBService;
    
    @Autowired
    private ChoreographerPlanValidator planValidator;

    @Autowired
    private ChoreographerPlanExecutionChecker planChecker;

    @Autowired
    private JmsTemplate jms;
    
    //=================================================================================================
	// methods

    //-------------------------------------------------------------------------------------------------
    @ApiOperation(value = "Return an echo message with the purpose of testing the core service availability", response = String.class, tags = { CoreCommonConstants.SWAGGER_TAG_CLIENT })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = CoreCommonConstants.SWAGGER_HTTP_200_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_UNAUTHORIZED, message = CoreCommonConstants.SWAGGER_HTTP_401_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = CoreCommonConstants.SWAGGER_HTTP_500_MESSAGE)
    })
    @GetMapping(path = CommonConstants.ECHO_URI)
    public String echoService() {
        return "Got it!";
    }
    
    //-------------------------------------------------------------------------------------------------
	@ApiOperation(value = "Return requested Plan entries by the given parameters.", response = ChoreographerPlanListResponseDTO.class, tags = { CoreCommonConstants.SWAGGER_TAG_MGMT })
    @ApiResponses (value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = GET_PlAN_MGMT_HTTP_200_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_BAD_REQUEST, message = GET_PLAN_MGMT_HTTP_400_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_UNAUTHORIZED, message = CoreCommonConstants.SWAGGER_HTTP_401_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = CoreCommonConstants.SWAGGER_HTTP_500_MESSAGE)
    })
    @GetMapping(path = PLAN_MGMT_URI, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody public ChoreographerPlanListResponseDTO getPlans(
            @RequestParam(name = CoreCommonConstants.REQUEST_PARAM_PAGE, required = false) final Integer page,
            @RequestParam(name = CoreCommonConstants.REQUEST_PARAM_ITEM_PER_PAGE, required = false) final Integer size,
            @RequestParam(name = CoreCommonConstants.REQUEST_PARAM_DIRECTION, defaultValue = CoreDefaults.DEFAULT_REQUEST_PARAM_DIRECTION_VALUE) final String direction,
            @RequestParam(name = CoreCommonConstants.REQUEST_PARAM_SORT_FIELD, defaultValue = CoreCommonConstants.COMMON_FIELD_NAME_ID) final String sortField) {
        logger.debug("New Plan GET request received with page: {} and item_per_page: {}.", page, size);

        final ValidatedPageParams validatedPageParams = CoreUtilities.validatePageParameters(page, size, direction, sortField);
        final ChoreographerPlanListResponseDTO planEntriesResponse = planDBService.getPlanEntriesResponse(validatedPageParams.getValidatedPage(),
        																								  validatedPageParams.getValidatedSize(),
        																								  validatedPageParams.getValidatedDirection(),
        																								  sortField);
        logger.debug("Plan with page: {} and item_per_page: {} retrieved successfully", page, size);

        return planEntriesResponse;
    }
	
    //-------------------------------------------------------------------------------------------------
	@ApiOperation(value = "Return the requested Plan entry.", response = ChoreographerPlanResponseDTO.class, tags = { CoreCommonConstants.SWAGGER_TAG_MGMT })
    @ApiResponses (value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = GET_PlAN_MGMT_HTTP_200_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_BAD_REQUEST, message = GET_PLAN_MGMT_HTTP_400_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_UNAUTHORIZED, message = CoreCommonConstants.SWAGGER_HTTP_401_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = CoreCommonConstants.SWAGGER_HTTP_500_MESSAGE)
    })
    @GetMapping(path = PLAN_MGMT_BY_ID_URI, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody public ChoreographerPlanResponseDTO getPlanById(@PathVariable(value = PATH_VARIABLE_ID) final long id) {
        logger.debug("New Plan GET request received with id: {}.", id);

        if (id < 1) {
            throw new BadPayloadException(ID_NOT_VALID_ERROR_MESSAGE, HttpStatus.SC_BAD_REQUEST, CommonConstants.CHOREOGRAPHER_URI + PLAN_MGMT_BY_ID_URI);
        }

        final ChoreographerPlanResponseDTO planEntryResponse = planDBService.getPlanByIdResponse(id);
        logger.debug("Plan entry with id: {} successfully retrieved!", id);

        return planEntryResponse;
    }
	
    //-------------------------------------------------------------------------------------------------
	@ApiOperation(value = "Remove the requested Plan entry.", tags = { CoreCommonConstants.SWAGGER_TAG_MGMT })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = DELETE_PLAN_HTTP_200_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_BAD_REQUEST, message = DELETE_PLAN_HTTP_400_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_UNAUTHORIZED, message = CoreCommonConstants.SWAGGER_HTTP_401_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = CoreCommonConstants.SWAGGER_HTTP_500_MESSAGE)
    })
    @DeleteMapping(path = PLAN_MGMT_BY_ID_URI)
    public void removePlanById(@PathVariable(value = PATH_VARIABLE_ID) final long id) {
        logger.debug("New Plan delete request received with id of {}.", id);

        if (id < 1) {
            throw new BadPayloadException(ID_NOT_VALID_ERROR_MESSAGE, HttpStatus.SC_BAD_REQUEST, CommonConstants.CHOREOGRAPHER_URI + PLAN_MGMT_BY_ID_URI);
        }

        planDBService.removePlanEntryById(id);
        logger.debug("Plan with id: {} successfully deleted!", id);
    }

    //-------------------------------------------------------------------------------------------------
    @ApiOperation(value = "Register a plan.",
    			  tags = { CoreCommonConstants.SWAGGER_TAG_MGMT })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_CREATED, message = POST_PLAN_MGMT_HTTP_201_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_BAD_REQUEST, message = POST_PLAN_MGMT_HTTP_400_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_UNAUTHORIZED, message = CoreCommonConstants.SWAGGER_HTTP_401_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = CoreCommonConstants.SWAGGER_HTTP_500_MESSAGE)
    })
    @PostMapping(path = PLAN_MGMT_URI, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(value = org.springframework.http.HttpStatus.CREATED)
    @ResponseBody public ChoreographerPlanResponseDTO registerPlan(@RequestBody final ChoreographerPlanRequestDTO request) {
        final ChoreographerPlanRequestDTO validatedPlan = planValidator.validatePlan(request, CommonConstants.CHOREOGRAPHER_URI + PLAN_MGMT_URI);

        return planDBService.createPlanResponse(validatedPlan);
    }

    //-------------------------------------------------------------------------------------------------
    @ApiOperation(value = "Initiate the start of one or more plans.",
            tags = { CoreCommonConstants.SWAGGER_TAG_MGMT })
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = START_SESSION_HTTP_200_MESSAGE, responseContainer = "List", response = ChoreographerRunPlanResponseDTO.class),
            @ApiResponse(code = HttpStatus.SC_BAD_REQUEST, message = START_PLAN_HTTP_400_MESSAGE, response= ErrorMessageDTO.class),
            @ApiResponse(code = HttpStatus.SC_UNAUTHORIZED, message = CoreCommonConstants.SWAGGER_HTTP_401_MESSAGE, response= ErrorMessageDTO.class),
            @ApiResponse(code = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = CoreCommonConstants.SWAGGER_HTTP_500_MESSAGE, response= ErrorMessageDTO.class)
    })
    @PostMapping(path = START_SESSION_MGMT_URI, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody public List<ChoreographerRunPlanResponseDTO> startPlans(@RequestBody final List<ChoreographerRunPlanRequestDTO> requests) {
    	logger.debug("startPlans started...");
    	
    	if (requests == null || requests.isEmpty()) {
    		throw new BadPayloadException("No plan specified to start.", HttpStatus.SC_BAD_REQUEST, CommonConstants.CHOREOGRAPHER_URI + START_SESSION_MGMT_URI);
    	}
    	
    	final List<ChoreographerRunPlanResponseDTO> results = new ArrayList<>(requests.size());
        for (final ChoreographerRunPlanRequestDTO request : requests) {
           final ChoreographerRunPlanResponseDTO response = planChecker.checkPlanForExecution(request);
           
           if (!Utilities.isEmpty(response.getErrorMessages())) {
        	   results.add(response);
           } else {
        	   final ChoreographerSession session = sessionDBService.initiateSession(request.getPlanId(), createNotifyUri(request));
        	   results.add(new ChoreographerRunPlanResponseDTO(request.getPlanId(), session.getId(), response.getNeedInterCloud()));
        	   
        	   logger.debug("Sending a message to {}.", ChoreographerService.START_SESSION_DESTINATION);
        	   jms.convertAndSend(ChoreographerService.START_SESSION_DESTINATION, new ChoreographerStartSessionDTO(session.getId(), request.getPlanId()));
           }
        }
           
        return results;
    }
    
    //-------------------------------------------------------------------------------------------------
	@ApiOperation(value = "Return the check report of the specified plan.", response = ChoreographerCheckPlanResponseDTO.class, tags = { CoreCommonConstants.SWAGGER_TAG_MGMT })
    @ApiResponses (value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = GET_CHECK_PLAN_MGMT_HTTP_200_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_BAD_REQUEST, message = GET_CHECK_PLAN_MGMT_HTTP_400_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_UNAUTHORIZED, message = CoreCommonConstants.SWAGGER_HTTP_401_MESSAGE),
            @ApiResponse(code = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = CoreCommonConstants.SWAGGER_HTTP_500_MESSAGE)
    })
    @GetMapping(path = CHECK_PLAN_MGMT_BY_ID_URI, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody public ChoreographerCheckPlanResponseDTO checkPlan(@PathVariable(value = PATH_VARIABLE_ID) final long id,
            														 @RequestParam(name = REQUEST_PARRAM_ALLOW_INTER_CLOUD, defaultValue = FALSE) final boolean allowIntercloud) { //TODO test this (new query param)
        logger.debug("New check plan GET request received with id: {}.", id);

        if (id < 1) {
            throw new BadPayloadException(ID_NOT_VALID_ERROR_MESSAGE, HttpStatus.SC_BAD_REQUEST, CommonConstants.CHOREOGRAPHER_URI + CHECK_PLAN_MGMT_BY_ID_URI);
        }

        final ChoreographerRunPlanResponseDTO result = planChecker.checkPlanForExecution(allowIntercloud, id);
        logger.debug("Check report for plan with id: {} successfully retrieved!", id);

        return new ChoreographerCheckPlanResponseDTO(id, result.getErrorMessages(), result.getNeedInterCloud());
    }

    //=================================================================================================
	// assistant methods
    
    //-------------------------------------------------------------------------------------------------
	private String createNotifyUri(final ChoreographerRunPlanRequestDTO request) {
		return Utilities.isEmpty(request.getNotifyAddress()) ? null
															 : request.getNotifyProtocol() + "://" + request.getNotifyAddress() + ":" + request.getNotifyPort() + "/" + request.getNotifyPath();
	}
}