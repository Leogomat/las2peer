package i5.las2peer.api.security;

/**
 * Represents an agent that can be unlocked using a passphrase.
 *
 */
public interface PassphraseAgent extends Agent {
	/**
	 * Unlocks this agent.
	 * 
	 * @param passphrase The passphrase to unlock this agent.
	 * @throws AgentAccessDeniedException If the passphrase is not valid.
	 * @throws AgentOperationFailedException If the agent's private key can not be deserialized.
	 */
	void unlock(String passphrase) throws AgentAccessDeniedException, AgentOperationFailedException;
}
