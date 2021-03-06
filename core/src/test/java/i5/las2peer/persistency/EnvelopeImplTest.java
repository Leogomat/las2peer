package i5.las2peer.persistency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import org.junit.Before;
import org.junit.Test;

import i5.las2peer.api.persistency.EnvelopeException;
import i5.las2peer.api.security.AgentException;
import i5.las2peer.p2p.LocalNode;
import i5.las2peer.p2p.LocalNodeManager;
import i5.las2peer.security.AgentContext;
import i5.las2peer.security.AgentImpl;
import i5.las2peer.security.InternalSecurityException;
import i5.las2peer.security.UserAgentImpl;
import i5.las2peer.serialization.MalformedXMLException;
import i5.las2peer.serialization.SerializationException;
import i5.las2peer.testing.MockAgentFactory;
import i5.las2peer.tools.CryptoException;

public class EnvelopeImplTest {

	LocalNode node;

	@Before
	public void setup() {
		node = new LocalNodeManager().launchNode();
	}

	@Test
	public void testFresh() throws MalformedXMLException, IOException, IllegalArgumentException, SerializationException,
			CryptoException, InternalSecurityException, EnvelopeException {
		AgentImpl owner = MockAgentFactory.getAdam();
		AgentImpl reader = MockAgentFactory.getEve();

		EnvelopeImpl envelope = new EnvelopeImpl("test", owner);
		assertEquals("test", envelope.getIdentifier());
		assertEquals(owner.getIdentifier(), envelope.getOwnerId());
		assertTrue(envelope.hasReader(owner));
		assertFalse(envelope.hasReader(reader));

		envelope.addReader(reader);
		assertTrue(envelope.hasReader(reader));

		envelope.revokeReader(reader);
		assertFalse(envelope.hasReader(reader));

		envelope.setPublic();
		assertFalse(envelope.hasReader(reader));
		assertFalse(envelope.hasReader(owner));
		assertFalse(envelope.isPrivate());
	}

	@Test
	public void testFromExisting() throws MalformedXMLException, IOException, IllegalArgumentException,
			SerializationException, CryptoException, InternalSecurityException, EnvelopeException, AgentException {
		UserAgentImpl owner = MockAgentFactory.getAdam();
		owner.unlock("adamspass");
		node.storeAgent(owner);
		AgentContext ownerContext = node.getAgentContext(owner);

		Collection<AgentImpl> readers = new ArrayList<>();
		readers.add(owner);

		EnvelopeVersion oldVersion = new EnvelopeVersion("test", owner.getPublicKey(), "content", readers);
		node.storeEnvelope(oldVersion, owner);

		EnvelopeImpl envelope = new EnvelopeImpl(oldVersion, ownerContext);
		assertEquals(owner.getIdentifier(), envelope.getOwnerId());
		assertEquals("content", envelope.getContent());
		assertTrue(envelope.hasReader(owner));

		envelope.revokeReader(owner);
		assertFalse(envelope.hasReader(owner));
	}

}
