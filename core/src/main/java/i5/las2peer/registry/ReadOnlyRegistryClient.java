package i5.las2peer.registry;

import i5.las2peer.logging.L2pLogger;
import i5.las2peer.registry.data.BlockchainTransactionData;
import i5.las2peer.registry.data.GenericTransactionData;
import i5.las2peer.registry.data.RegistryConfiguration;
import i5.las2peer.registry.data.SenderReceiverDoubleKey;
import i5.las2peer.registry.data.ServiceDeploymentData;
import i5.las2peer.registry.data.ServiceReleaseData;
import i5.las2peer.registry.data.UserData;
import i5.las2peer.registry.data.UserProfileData;
import i5.las2peer.registry.exceptions.EthereumException;
import i5.las2peer.registry.exceptions.NotFoundException;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.admin.Admin;
import org.web3j.protocol.admin.methods.response.PersonalUnlockAccount;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCoinbase;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;
import org.web3j.protocol.http.HttpService;
import org.web3j.tuples.generated.Tuple2;
import org.web3j.tuples.generated.Tuple4;
import org.web3j.tuples.generated.Tuple6;
import org.web3j.utils.Convert;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Facade providing simple read-only access to the registry smart
 * contracts.
 *
 * @see ReadWriteRegistryClient
 */
public class ReadOnlyRegistryClient {
	Web3j web3j;
	Admin web3j_admin;
	Contracts.ContractsConfig contractsConfig;
	Contracts contracts;
	BlockchainObserver observer;
	//long gasPrice;
	//long gasLimit;
	BigInteger gasPrice;
	BigInteger gasLimit;

	// note: these are also baked into the TransactionManager, which is in
	// turn baked into the contract wrappers. so we don't need this (and do
	// not use) this field for contract function invocations.
	// as of this writing, this is only used for the sendEther method in the
	// ReadWriteRegistryClient (which is only used for debugging)
	Credentials credentials;

	protected final L2pLogger logger = L2pLogger.getInstance(ReadWriteRegistryClient.class);

	/**
	 * Create client providing access to read-only registry functions.
	 * @param registryConfiguration addresses of registry contracts and
	 *                              Ethereum client HTTP JSON RPC API
	 *                              endpoint
	 */
	public ReadOnlyRegistryClient(RegistryConfiguration registryConfiguration) {
		this(registryConfiguration, null);
	}

	ReadOnlyRegistryClient(RegistryConfiguration registryConfiguration, Credentials credentials) {
		web3j = Web3j.build(new HttpService(registryConfiguration.getEndpoint()));
		web3j_admin = Admin.build(new HttpService(registryConfiguration.getEndpoint()));

		contractsConfig = new Contracts.ContractsConfig(registryConfiguration.getCommunityTagIndexAddress(),
				registryConfiguration.getUserRegistryAddress(), registryConfiguration.getServiceRegistryAddress(),
				registryConfiguration.getReputationRegistryAddress(), registryConfiguration.getEndpoint());

		observer = BlockchainObserver.getInstance(contractsConfig);
		
		long _gasPrice = registryConfiguration.getGasPrice();
		this.gasPrice = BigInteger.valueOf(_gasPrice);
		
		long _gasLimit = registryConfiguration.getGasLimit();
		this.gasLimit = BigInteger.valueOf(_gasLimit);

		logger.info("creating smart contract wrapper with credentials:" + credentials.getAddress());
		contracts = new Contracts.ContractsBuilder(contractsConfig)
				.setGasOptions(_gasPrice, _gasLimit)
				.setCredentials(credentials) // may be null, that's okay here
				.build();

		this.credentials = credentials;
	}
	

	public BigInteger getGasPrice() {
		return gasPrice;
	}

	public BigInteger getGasLimit() {
		return gasLimit;
	}

	public boolean unlockAccount(String accountAddress, String accountPassword) {
		PersonalUnlockAccount personalUnlockAccount;
		try {
			personalUnlockAccount = web3j_admin.personalUnlockAccount(accountAddress, accountPassword).sendAsync()
					.get();
		} catch (InterruptedException | ExecutionException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
			return false;
		}
		return personalUnlockAccount.accountUnlocked();
	}

	/**
	 * Return version string of connected Ethereum client.
	 * @deprecated there's no reason to reveal this implementation
	 * 	           detail, so this may be removed
	 */
	// this is the only place where `web3j` is (directly) accessed
	@Deprecated
	public String getEthClientVersion() throws EthereumException {
		try {
			Web3ClientVersion web3ClientVersion = web3j.web3ClientVersion().send();
			return web3ClientVersion.getWeb3ClientVersion();
		} catch (IOException e) {
			throw new EthereumException("Failed to get client version", e);
		}
	}

	private String getTagDescription(String tagName) throws EthereumException {
		try {
			return contracts.communityTagIndex.viewDescription(Util.padAndConvertString(tagName, 32)).send();
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Tag name invalid (too long?)", e);
		} catch (Exception e) {
			throw new EthereumException(e);
		}
	}

	/**
	 * Return true if user name is both valid and not already taken and
	 * thus can be registered.
	 * @param name user name consisting of 1 to 32 Unicode characters
	 */
	public boolean usernameIsAvailable(String name) throws EthereumException {
		try {
			return contracts.userRegistry.nameIsAvailable(Util.padAndConvertString(name, 32)).send();
		} catch (Exception e) {
			throw new EthereumException(e);
		}
	}

	/**
	 * Return true if user name is both valid, as encoded in the
	 * registry smart contract code.
	 *
	 * (Any non-empty String of up to 32 characters should work, but
	 * let's not press our luck.)
	 *
	 * @param name user name consisting of 1 to 32 Unicode characters
	 */
	public boolean usernameIsValid(String name) throws EthereumException {
		try {
			return contracts.userRegistry.nameIsValid(Util.padAndConvertString(name, 32)).send();
		} catch (Exception e) {
			throw new EthereumException(e);
		}
	}

	/**
	 * Retrieve user data stored in registry for given name.
	 * @param name user name consisting of 1 to 32 Unicode characters
	 * @return user data object containing ID and owner address, or
	 * 		   <code>null</code> if user name is not taken
	 */
	public UserData getUser(String name) throws EthereumException, NotFoundException {
		Tuple4<byte[], byte[], byte[], String> userAsTuple;
		try {
			userAsTuple = contracts.userRegistry.users(Util.padAndConvertString(name, 32)).send();
		} catch (Exception e) {
			throw new EthereumException("Could not get user", e);
		}

		byte[] returnedName = userAsTuple.getValue1();
		if (Arrays.equals(returnedName, new byte[returnedName.length])) {
			// name is 0s, meaning entry does not exist
			throw new NotFoundException("User name apparently not registered.");
		}

		return new UserData(userAsTuple.getValue1(), userAsTuple.getValue2(), userAsTuple.getValue3(), userAsTuple.getValue4());
	}

	public UserProfileData getProfile(String address) throws EthereumException, NotFoundException {
		Tuple6<String, byte[], BigInteger, BigInteger, BigInteger, BigInteger> profileAsTuple;
		try {
			profileAsTuple = contracts.reputationRegistry.profiles(address).send();
			logger.info("found user profile: " + profileAsTuple.toString());
		} catch (Exception e) {
			throw new EthereumException("Could not get profile", e);
		}

		String returnedAddress = profileAsTuple.getValue1();
		if (returnedAddress == "") {
			throw new NotFoundException("User profile apparently not registered.");
		}
		
		/*
		 * owner userName cumulativeScore noTransactionsSent noTransactionsReceived
		 */
		return new UserProfileData(
			profileAsTuple.getValue1(), // owner
			profileAsTuple.getValue2(), // username
			profileAsTuple.getValue3(), // score
			profileAsTuple.getValue4(), // txsent
			profileAsTuple.getValue5(), // txrcvd
			profileAsTuple.getValue6() // index
		);
	}

	public float getUserRating(String ethAddress) {
		float userRatingScore_Raw = 0f;
		UserProfileData userProfileData = null;
		try {
			userProfileData = this.getProfile(ethAddress);
			if (userProfileData != null && !userProfileData.getOwner().equals("0x0000000000000000000000000000000000000000")) 
			{
				if (userProfileData.getNoTransactionsRcvd().compareTo(BigInteger.ZERO) == 0) {
					logger.info("[User Reputation]: valid reputation profile, no incoming reputation yet." );
				} 
				else 
				{
					userRatingScore_Raw = userProfileData.getStarRating();
					logger.info("[User Reputation]: valid reputation profile, score: " + Float.toString(userRatingScore_Raw) );
				}
			}
			else
			{
				logger.info("[User Reputation]: no valid reputation profile" );
			}
		} catch (EthereumException | NotFoundException e) {
			logger.severe("[User Reputation]: failed to get user reputation for " + ethAddress );
			e.printStackTrace();
			return 0;
		}
		return userRatingScore_Raw;
	}

	/**
	 * Look up author/owner for a given service.
	 * @param serviceName service package name
	 * @return author owning the service name
	 */
	public String lookupServiceAuthor(String serviceName) throws EthereumException, NotFoundException {
		byte[] serviceNameHash = Util.soliditySha3(serviceName);
		Tuple2<String, byte[]> serviceNameAndOwner;
		try {
			serviceNameAndOwner = contracts.serviceRegistry.services(serviceNameHash).send();
		} catch (Exception e) {
			throw new EthereumException("Failed to look up service author", e);
		}

		String ownerName = Util.recoverString(serviceNameAndOwner.getValue2());

		if (ownerName.equals("\u0000")) {
			throw new NotFoundException("Service not registered, can't get author.");
		} else {
			return ownerName;
		}
	}

	/** @return map of tags to descriptions */
	public ConcurrentMap<String, String> getTags() {
		return observer.tags;
	}

	/** @return set of registered service (package) names */
	public Set<String> getServiceNames() {
		return observer.serviceNameToAuthor.keySet();
	}

	/** @return map of service names to their authors */
	public ConcurrentMap<String, String> getServiceAuthors() {
		return observer.serviceNameToAuthor;
	}

	public String getServiceAuthor(String service) {
		if ( !observer.serviceNameToAuthor.containsKey(service) )
			return "";
		return observer.serviceNameToAuthor.get(service);
	}
	
	/** @return map of profile owners to their usernames */
	public ConcurrentMap<String, String> getUserProfiles() {
		return observer.profiles;
	}
	
	/** @return map of users to their registration time stamps */
	public ConcurrentMap<String, String> getUserRegistrations() {
		return observer.users;
	}

	/** @return map of names to service release objects */
	public ConcurrentMap<String, List<ServiceReleaseData>> getServiceReleases() {
		return observer.releases;
	}

	/** @return set of all active service deployments */
	public Set<ServiceDeploymentData> getDeployments() {
		Set<ServiceDeploymentData> activeDeployments = new HashSet<>();

		observer.deployments.values().forEach(innerMap -> {
			innerMap.values().forEach(deploymentData -> {
				if (!deploymentData.hasEnded()) {
					activeDeployments.add(deploymentData);
				}
			});
		});

		return activeDeployments;
	}

	public Set<ServiceDeploymentData> getDeployments(String serviceName) {
		return getDeployments().stream().filter(d -> d.getServicePackageName().equals(serviceName)).collect(Collectors.toSet());
	}

	public Set<ServiceDeploymentData> getDeployments(String serviceName, String version) {
		return getDeployments().stream().filter(d -> (d.getServicePackageName().equals(serviceName) && d.getVersion().equals(version))).collect(Collectors.toSet());
	}

	public String getAccountBalance(String ethereumAddress) throws EthereumException
	{
		EthGetBalance ethGetBalance = null;
		try {
			ethGetBalance = this.web3j
				  .ethGetBalance(ethereumAddress, DefaultBlockParameterName.LATEST)
				  .sendAsync()
				  .get();
		} catch (Exception e) {
			throw new EthereumException(e);
		}
		BigInteger wei = ethGetBalance.getBalance();
		java.math.BigDecimal tokenValue = Convert.fromWei(String.valueOf(wei), Convert.Unit.ETHER);
		String strTokenAmount = String.valueOf(tokenValue);
		return strTokenAmount;
	}

	public ConcurrentMap<SenderReceiverDoubleKey, List<Transaction>> getTransactionLog() {
		return observer.transactionLog;
	}

	/***
	 * Query no. of service announcements which occurred since provide block
	 * @param largerThanBlockNo block number to start querying at
	 * @param searchingForService service which is to be found
	 * @return HashMap< ServiceName, NoOfAnnouncements >
	 */
	public HashMap<String, Integer> getNoOfServiceAnnouncementSinceBlockOrderedByHostingNode(BigInteger largerThanBlockNo, String searchingForService)
	{
		return observer.getNoOfServiceAnnouncementSinceBlockOrderedByHostingNode(largerThanBlockNo, searchingForService);
	}

	public BlockchainTransactionData getTransactionInfo(String txHash) throws EthereumException {
		EthTransaction ethTransaction;
		try {
			ethTransaction = getTransactionByTxHash(txHash);
		} catch (IOException e) {
			throw new EthereumException("cannot get transaction by txhash", e);
		}
		Optional<org.web3j.protocol.core.methods.response.Transaction> o = ethTransaction.getTransaction();
		if (!o.isPresent()) {
			throw new EthereumException("transaction not found");
		}
		org.web3j.protocol.core.methods.response.Transaction t = o.get();

		BlockchainTransactionData btd = new BlockchainTransactionData(t.getBlockNumber(), t.getGas(), t.getGasPrice(),
				t.getNonce(), t.getTransactionIndex(), t.getFrom(), t.getInput(), t.getTo());
		return btd;
	}

	public EthTransaction getTransactionByTxHash(String txHash) throws IOException {
		return web3j.ethGetTransactionByHash(txHash).send();
	}

	public List<GenericTransactionData> getTransactionLogBySender(String sender) {
		return observer.getTransactionLogBySender(sender);
	}

	public List<GenericTransactionData> getTransactionLogByReceiver(String receiver) {
		return observer.getTransactionLogByReceiver(receiver);
	}
	
	/**
	 * Return the nonce (tx count) for the specified address.
	 * https://github.com/matthiaszimmermann/web3j_demo / Web3jUtils
	 * @param address target address
	 * @return nonce
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	public BigInteger getNonce(String address) throws InterruptedException, ExecutionException {
		EthGetTransactionCount ethGetTransactionCount = 
				web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).sendAsync().get();

		return ethGetTransactionCount.getTransactionCount();
	}
	
	/**
	 * Queries the coin base = the first account in the chain
	 * By design, this is the account which the hosting node uses for mining in the background
	 * https://github.com/matthiaszimmermann/web3j_demo / Web3jUtils
	 * @return coinbase address
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	public EthCoinbase getCoinbase() throws InterruptedException, ExecutionException {
		return web3j
				.ethCoinbase()
				.sendAsync()
				.get();
	}
	
	/**
	 * Waits for the receipt for the transaction specified by the provided tx hash.
	 * Makes 30 attempts (waiting 1 sec. between attempts) to get the receipt object.
	 * In the happy case the tx receipt object is returned.
	 * Otherwise, a runtime exception is thrown. 
	 * https://github.com/matthiaszimmermann/web3j_demo / Web3jUtils
	 * @param transactionHash
	 * @return 
	 * @throws ExecutionException 
	 * @throws InterruptedException 
	 * @throws Exception
	 */
	protected TransactionReceipt waitForReceipt(String transactionHash) 
			throws InterruptedException, ExecutionException 
	{

		int attempts = 60; // const CONFIRMATION_ATTEMPTS
		int sleep_millis = 1000; // const SLEEP_DURATION
		
		Optional<TransactionReceipt> receipt = this.getReceipt(transactionHash);

		while(attempts-- > 0 && !receipt.isPresent()) {
			Thread.sleep(sleep_millis);
			receipt = getReceipt(transactionHash);
		}

		if (attempts <= 0) {
			throw new RuntimeException("No Tx receipt received for hash " + transactionHash);
		}

		return receipt.get();
	}

	protected void waitForTransactionReceipt(String txHash) throws EthereumException {
		logger.info("waiting for receipt on [" + txHash + "]... ");
		TransactionReceipt txR;
		try {
			txR = waitForReceipt(txHash);
			if (txR == null) {
				throw new EthereumException("Transaction sent, no receipt returned. Wait more?");
			}
			if (!txR.isStatusOK()) {
				logger.warning("trx fail with status " + txR.getStatus());
				// String gasUsed =
				// String.valueOf(Convert.fromWei(String.valueOf(txR.getCumulativeGasUsedRaw()),
				// Convert.Unit.ETHER));
				logger.warning("gas used " + txR.getCumulativeGasUsed());
				if (!txHash.equals(txR.getTransactionHash())) {
					logger.warning("transaction hash mismatch");
				}
				logger.warning(txR.toString());
				throw new EthereumException("could not send transaction, transaction receipt not ok");
			}
		} catch (InterruptedException | ExecutionException e) {
			throw new EthereumException("Wait for receipt interrupted or failed.");
		}
		logger.info("receipt for [" + txHash + "] received.");

		// return txR;
	}

	/**
	 * Returns the TransactionRecipt for the specified tx hash as an optional.
	 * https://github.com/matthiaszimmermann/web3j_demo / Web3jUtils
	 * @param transactionHash
	 * @return transactionReceipt
	 * @throws ExecutionException 
	 * @throws InterruptedException
	 */
	private Optional<TransactionReceipt> getReceipt(String transactionHash) 
			throws InterruptedException, ExecutionException
	{
		EthGetTransactionReceipt receipt = web3j
				.ethGetTransactionReceipt(transactionHash)
				.sendAsync()
				.get();

		return receipt.getTransactionReceipt();
	}
	

	/**
	 * Converts the provided Wei amount (smallest value Unit) to Ethers.
	 * https://github.com/matthiaszimmermann/web3j_demo / Web3jUtils
	 * @param wei
	 * @return
	 */
	public BigDecimal weiToEther(BigInteger wei) {
		return Convert.fromWei(wei.toString(), Convert.Unit.ETHER);
	}
	
	/**
	 * Converts the provided Ether amount to Wei (smallest value Unit) .
	 * https://github.com/matthiaszimmermann/web3j_demo / Web3jUtils
	 * @param ether
	 * @return
	 */
	public BigInteger etherToWei(BigDecimal ether) {
		return Convert.toWei(ether, Convert.Unit.ETHER).toBigInteger();
	}
	
	/*
	@Deprecated
	public Map<String, List<ServiceDeploymentData>> getServiceDeployments() {
		// just getting rid of the redundant (for our purposes) nested map
		// and filtering out those deployments that have ended (we need to keep track of them internally, but don't
		// want to expose them)
		return observer.deployments.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, value -> new ArrayList<>(value.getValue().values())))
				.entrySet().stream() // OMG
				.filter(e -> e.getValue().stream().anyMatch(deploymentData -> deploymentData.hasEnded()))
				.collect(Collectors.toMap(e -> e.getKey(), e-> e.getValue())); // are you serious?
		// ahhhahahahahaaahaaaa embrace the dark side, let it flow through you!
	}
	*/
}
