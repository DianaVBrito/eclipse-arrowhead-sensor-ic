package eu.arrowhead.core.gatekeeper.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.security.PublicKey;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.Session;
import javax.jms.StreamMessage;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicSubscriber;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;

import eu.arrowhead.common.CommonConstants;
import eu.arrowhead.common.database.entity.Cloud;
import eu.arrowhead.common.database.entity.Relay;
import eu.arrowhead.common.dto.ICNProposalRequestDTO;
import eu.arrowhead.common.dto.ICNProposalResponseDTO;
import eu.arrowhead.common.dto.RelayType;
import eu.arrowhead.common.exception.ArrowheadException;
import eu.arrowhead.common.exception.TimeoutException;
import eu.arrowhead.common.http.HttpService;
import eu.arrowhead.core.gatekeeper.relay.GatekeeperRelayClient;
import eu.arrowhead.core.gatekeeper.relay.GatekeeperRelayResponse;
import eu.arrowhead.core.gatekeeper.relay.GeneralAdvertisementResult;
import eu.arrowhead.core.gatekeeper.service.matchmaking.GatekeeperMatchmakingAlgorithm;
import eu.arrowhead.core.gatekeeper.service.matchmaking.GatekeeperMatchmakingParameters;

@RunWith(SpringRunner.class)
public class GatekeeperDriverICNTest {

	//=================================================================================================
	// members
	
	@InjectMocks
	private GatekeeperDriver testingObject;
	
	@Mock
	private GatekeeperMatchmakingAlgorithm gatekeeperMatchmaker;
	
	@Mock
	private HttpService httpService;
	
	private GatekeeperRelayClient relayClient;
	
	//=================================================================================================
	// methods
	
	//-------------------------------------------------------------------------------------------------
	@Before
	public void setUp() {
		relayClient = mock(GatekeeperRelayClient.class, "relayClient");
		ReflectionTestUtils.setField(testingObject, "relayClient", relayClient);
	}
	
	//-------------------------------------------------------------------------------------------------
	@Test(expected = IllegalArgumentException.class)
	public void testSendICNProposalTargetCloudNull() {
		testingObject.sendICNProposal(null, null);
	}
	
	//-------------------------------------------------------------------------------------------------
	@Test(expected = IllegalArgumentException.class)
	public void testSendICNProposalRequestNull() {
		testingObject.sendICNProposal(new Cloud(), null);
	}
	
	//-------------------------------------------------------------------------------------------------
	@Test(expected = ArrowheadException.class)
	public void testSendICNProposalRelayProblem() throws JMSException {
		final Relay relay = new Relay("localhost", 12345, false, false, RelayType.GATEKEEPER_RELAY);
		when(gatekeeperMatchmaker.doMatchmaking(any(GatekeeperMatchmakingParameters.class))).thenReturn(relay);

		when(relayClient.createConnection(any(String.class), anyInt())).thenThrow(JMSException.class);
		
		testingObject.sendICNProposal(new Cloud(), new ICNProposalRequestDTO());
	}
	
	//-------------------------------------------------------------------------------------------------
	@Test(expected = TimeoutException.class)
	public void testSendICNProposalNoAcknowledgement() throws JMSException {
		final Relay relay = new Relay("localhost", 12345, false, false, RelayType.GATEKEEPER_RELAY);
		when(gatekeeperMatchmaker.doMatchmaking(any(GatekeeperMatchmakingParameters.class))).thenReturn(relay);
		
		when(relayClient.createConnection(any(String.class), anyInt())).thenReturn(getTestSession());
		when(relayClient.publishGeneralAdvertisement(any(Session.class), any(String.class), any(String.class))).thenReturn(null);
		
		final Cloud targetCloud = new Cloud("aitia", "testcloud2", true, true, false, "abcd");
		testingObject.sendICNProposal(targetCloud, new ICNProposalRequestDTO());
	}
	
	//-------------------------------------------------------------------------------------------------
	@Test(expected = TimeoutException.class)
	public void testSendICNProposalNoResponse() throws JMSException {
		final Relay relay = new Relay("localhost", 12345, false, false, RelayType.GATEKEEPER_RELAY);
		when(gatekeeperMatchmaker.doMatchmaking(any(GatekeeperMatchmakingParameters.class))).thenReturn(relay);
		
		when(relayClient.createConnection(any(String.class), anyInt())).thenReturn(getTestSession());
		final GeneralAdvertisementResult gaResult = new GeneralAdvertisementResult(getTestMessageConsumer(), "gatekeeper.testcloud1.aitia.arrowhead.eu", getDummyPublicKey(), "1234");
		when(relayClient.publishGeneralAdvertisement(any(Session.class), any(String.class), any(String.class))).thenReturn(gaResult);
		when(relayClient.sendRequestAndReturnResponse(any(Session.class), any(GeneralAdvertisementResult.class), any())).thenReturn(null);
		
		final Cloud targetCloud = new Cloud("aitia", "testcloud2", true, true, false, "abcd");
		testingObject.sendICNProposal(targetCloud, new ICNProposalRequestDTO());
	}
	
	//-------------------------------------------------------------------------------------------------
	@Test
	public void testSendICNProposalEverythingOK() throws JMSException {
		final Relay relay = new Relay("localhost", 12345, false, false, RelayType.GATEKEEPER_RELAY);
		when(gatekeeperMatchmaker.doMatchmaking(any(GatekeeperMatchmakingParameters.class))).thenReturn(relay);
		
		when(relayClient.createConnection(any(String.class), anyInt())).thenReturn(getTestSession());
		final GeneralAdvertisementResult gaResult = new GeneralAdvertisementResult(getTestMessageConsumer(), "gatekeeper.testcloud1.aitia.arrowhead.eu", getDummyPublicKey(), "1234");
		when(relayClient.publishGeneralAdvertisement(any(Session.class), any(String.class), any(String.class))).thenReturn(gaResult);
		final GatekeeperRelayResponse response = new GatekeeperRelayResponse("1234", CommonConstants.RELAY_MESSAGE_TYPE_ICN_PROPOSAL, new ICNProposalResponseDTO());
		when(relayClient.sendRequestAndReturnResponse(any(Session.class), any(GeneralAdvertisementResult.class), any())).thenReturn(response);
		
		final Cloud targetCloud = new Cloud("aitia", "testcloud2", true, true, false, "abcd");
		final ICNProposalResponseDTO result = testingObject.sendICNProposal(targetCloud, new ICNProposalRequestDTO());
		Assert.assertNotNull(result);
	}
	
	//=================================================================================================
	// assistant methods
	
	//-------------------------------------------------------------------------------------------------
	@SuppressWarnings("serial")
	private PublicKey getDummyPublicKey() {
		return new PublicKey() {
			
			//-------------------------------------------------------------------------------------------------
			public String getFormat() { return null; }
			public byte[] getEncoded() { return null; }
			public String getAlgorithm() { return null; }
		};
	}

	//-------------------------------------------------------------------------------------------------
	public Session getTestSession() {
		return new Session() {

			//-------------------------------------------------------------------------------------------------
			public void close() throws JMSException {}
			public Queue createQueue(final String queueName) throws JMSException { return null;	}
			public Topic createTopic(final String topicName) throws JMSException { return null;	}
			public MessageConsumer createConsumer(final Destination destination) throws JMSException { return null; }
			public MessageProducer createProducer(final Destination destination) throws JMSException { return null;	}
			public TextMessage createTextMessage(final String text) throws JMSException { return null; }
			public BytesMessage createBytesMessage() throws JMSException { return null; }
			public MapMessage createMapMessage() throws JMSException { return null; }
			public Message createMessage() throws JMSException { return null; }
			public ObjectMessage createObjectMessage() throws JMSException { return null; }
			public ObjectMessage createObjectMessage(final Serializable object) throws JMSException { return null; }
			public StreamMessage createStreamMessage() throws JMSException { return null; }
			public TextMessage createTextMessage() throws JMSException { return null; }
			public boolean getTransacted() throws JMSException { return false; 	}
			public int getAcknowledgeMode() throws JMSException { return 0; }
			public void commit() throws JMSException {}
			public void rollback() throws JMSException {}
			public void recover() throws JMSException {}
			public MessageListener getMessageListener() throws JMSException { return null; }
			public void setMessageListener(final MessageListener listener) throws JMSException {}
			public void run() {}
			public MessageConsumer createConsumer(final Destination destination, final String messageSelector) throws JMSException { return null; }
			public MessageConsumer createConsumer(final Destination destination, final String messageSelector, final boolean noLocal) throws JMSException { return null; }
			public MessageConsumer createSharedConsumer(final Topic topic, final String sharedSubscriptionName) throws JMSException { return null; }
			public MessageConsumer createSharedConsumer(final Topic topic, final String sharedSubscriptionName, final String messageSelector) throws JMSException { return null; }
			public TopicSubscriber createDurableSubscriber(final Topic topic, final String name) throws JMSException { return null; }
			public TopicSubscriber createDurableSubscriber(final Topic topic, final String name, final String messageSelector, final boolean noLocal) throws JMSException { return null; }
			public MessageConsumer createDurableConsumer(final Topic topic, final String name) throws JMSException { return null; }
			public MessageConsumer createDurableConsumer(final Topic topic, final String name, final String messageSelector, final boolean noLocal) throws JMSException { return null; }
			public MessageConsumer createSharedDurableConsumer(final Topic topic, final String name) throws JMSException { return null; }
			public MessageConsumer createSharedDurableConsumer(final Topic topic, final String name, final String messageSelector) throws JMSException { return null;	}
			public QueueBrowser createBrowser(final Queue queue) throws JMSException { return null; }
			public QueueBrowser createBrowser(final Queue queue, final String messageSelector) throws JMSException { return null; }
			public TemporaryQueue createTemporaryQueue() throws JMSException { return null; }
			public TemporaryTopic createTemporaryTopic() throws JMSException { return null;	}
			public void unsubscribe(final String name) throws JMSException {}

		};
	}
	
	//-------------------------------------------------------------------------------------------------
	public MessageConsumer getTestMessageConsumer() {
		return new MessageConsumer() {

			//-------------------------------------------------------------------------------------------------
			public Message receive(final long timeout) throws JMSException { return null; }
			public void close() throws JMSException {}
			public String getMessageSelector() throws JMSException { return null; }
			public MessageListener getMessageListener() throws JMSException { return null; }
			public void setMessageListener(final MessageListener listener) throws JMSException {}
			public Message receive() throws JMSException { return null; }
			public Message receiveNoWait() throws JMSException { return null; }
		};
	}
}