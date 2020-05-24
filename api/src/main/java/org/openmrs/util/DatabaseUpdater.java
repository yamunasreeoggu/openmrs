/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.RuntimeEnvironment;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.changelog.ChangeLogIterator;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.filter.ChangeSetFilterResult;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.changelog.filter.DbmsChangeSetFilter;
import liquibase.changelog.filter.ShouldRunChangeSetFilter;
import liquibase.changelog.visitor.UpdateVisitor;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.exception.LockException;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;
import liquibase.parser.core.xml.XMLChangeLogSAXParser;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.apache.commons.io.IOUtils;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.context.Context;
import org.openmrs.liquibase.ChangeLogDetective;
import org.openmrs.liquibase.ChangeLogVersionFinder;
import org.openmrs.liquibase.LiquibaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class uses Liquibase to update the database. <br>
 * <br>
 * See src/main/resources/liquibase-update-to-latest.xml for the changes. This class will also run
 * arbitrary liquibase xml files on the associated database as well. Details for the database are
 * taken from the openmrs runtime properties.
 *
 * @since 1.5
 */
public class DatabaseUpdater {
	
	private static final Logger log = LoggerFactory.getLogger(DatabaseUpdater.class);
	
	private static final String EMPTY_CHANGE_LOG_FILE = "liquibase-empty-changelog.xml";
	
	public static final String CONTEXT = "core";
	
	public static final String DATABASE_UPDATES_LOG_FILE = "liquibaseUpdateLogs.txt";
	
	private static Integer authenticatedUserId;
	
	private static final ChangeLogDetective changeLogDetective;
	
	private static final ChangeLogVersionFinder changeLogVersionFinder;
	
	static {
		changeLogDetective = new ChangeLogDetective();
		changeLogVersionFinder = new ChangeLogVersionFinder();
	}
	
	/**
	 * Holds the update warnings generated by the custom liquibase changesets as they are executed
	 */
	private static volatile List<String> updateWarnings = null;
	
	/**
	 * Convenience method to run the changesets using Liquibase to bring the database up to a version
	 * compatible with the code
	 *
	 * @throws InputRequiredException if the changelog file requires some sort of user input. The error
	 *             object will list of the user prompts and type of data for each prompt
	 * @see #executeChangelog(String, Map)
	 */
	public static void executeChangelog() throws DatabaseUpdateException, InputRequiredException {
		executeChangelog((String) null, (Map<String, Object>) null);
	}
	
	/**
	 * Run changesets on database using Liquibase to get the database up to the most recent version
	 *
	 * @param changelog the liquibase changelog file to use (or null to use the default file)
	 * @param userInput nullable map from question to user answer. Used if a call to update(null) threw
	 *            an {@link InputRequiredException}
	 * @throws DatabaseUpdateException
	 * @throws InputRequiredException
	 */
	public static void executeChangelog(String changelog, Map<String, Object> userInput)
	        throws DatabaseUpdateException, InputRequiredException {
		
		log.debug("Executing changelog: " + changelog);
		
		executeChangelog(changelog, userInput);
	}
	
	/**
	 * Interface used for callbacks when updating the database. Implement this interface and pass it to
	 * {@link DatabaseUpdater#executeChangelog(String, ChangeSetExecutorCallback)}
	 */
	public interface ChangeSetExecutorCallback {
		
		/**
		 * This method is called after each changeset is executed.
		 *
		 * @param changeSet the liquibase changeset that was just run
		 * @param numChangeSetsToRun the total number of changesets in the current file
		 */
		public void executing(ChangeSet changeSet, int numChangeSetsToRun);
	}
	
	/**
	 * Executes the given changelog file. This file is assumed to be on the classpath.
	 *
	 * @param changelog The string filename of a liquibase changelog xml file to run
	 * @return A list of messages or warnings generated by the executed changesets
	 * @throws InputRequiredException if the changelog file requires some sort of user input. The error
	 *             object will list of the user prompts and type of data for each prompt
	 */
	public static List<String> executeChangelog(String changelog, ChangeSetExecutorCallback callback)
	        throws DatabaseUpdateException, InputRequiredException {
		log.debug("installing the tables into the database");
		
		if (changelog == null) {
			throw new IllegalArgumentException("changelog must not be null");
		}
		
		try {
			log.debug("executing liquibase changelog " + changelog);
			return executeChangelog(changelog, new Contexts(CONTEXT), callback, null);
		}
		
		catch (Exception e) {
			throw new DatabaseUpdateException("There was an error while updating the database to the latest. file: "
			        + changelog + ". Error: " + e.getMessage(), e);
		}
	}
	
	/**
	 * This code was borrowed from the liquibase jar so that we can call the given callback function.
	 *
	 * @param changeLogFile the file to execute
	 * @param contexts the liquibase changeset context
	 * @param callback the function to call after every changeset
	 * @return A list of messages or warnings generated by the executed changesets
	 * @throws Exception
	 */
	public static List<String> executeChangelog(String changeLogFile, Contexts contexts, ChangeSetExecutorCallback callback,
	        ClassLoader cl) throws Exception {
		final class OpenmrsUpdateVisitor extends UpdateVisitor {
			
			private ChangeSetExecutorCallback callback;
			
			private int numChangeSetsToRun;
			
			public OpenmrsUpdateVisitor(Database database, ChangeSetExecutorCallback callback, int numChangeSetsToRun) {
				super(database, null);
				this.callback = callback;
				this.numChangeSetsToRun = numChangeSetsToRun;
			}
			
			@Override
			public void visit(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database,
			        Set<ChangeSetFilterResult> filterResults) throws LiquibaseException {
				if (callback != null) {
					callback.executing(changeSet, numChangeSetsToRun);
				}
				super.visit(changeSet, databaseChangeLog, database, filterResults);
			}
		}
		
		if (cl == null) {
			cl = OpenmrsClassLoader.getInstance();
		}
		
		log.debug("Setting up liquibase object to run changelog: " + changeLogFile);
		Liquibase liquibase = getLiquibase(changeLogFile, cl);
		
		int numChangeSetsToRun = liquibase.listUnrunChangeSets(contexts, new LabelExpression()).size();
		Database database = null;
		LockService lockHandler = null;
		
		try {
			database = liquibase.getDatabase();
			lockHandler = LockServiceFactory.getInstance().getLockService(database);
			lockHandler.waitForLock();
			
			ResourceAccessor openmrsFO = new ClassLoaderFileOpener(cl);
			ResourceAccessor fsFO = new FileSystemResourceAccessor();
			
			DatabaseChangeLog changeLog = new XMLChangeLogSAXParser().parse(changeLogFile, new ChangeLogParameters(),
			    new CompositeResourceAccessor(openmrsFO, fsFO));
			changeLog.setChangeLogParameters(liquibase.getChangeLogParameters());
			changeLog.validate(database);
			
			ChangeLogIterator logIterator = new ChangeLogIterator(changeLog, new ShouldRunChangeSetFilter(database),
			        new ContextChangeSetFilter(contexts), new DbmsChangeSetFilter(database));
			
			// ensure that the change log history service is initialised
			//
			ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(database).init();
			
			logIterator.run(new OpenmrsUpdateVisitor(database, callback, numChangeSetsToRun),
			    new RuntimeEnvironment(database, contexts, new LabelExpression()));
		}
		catch (LiquibaseException e) {
			throw e;
		}
		finally {
			try {
				lockHandler.releaseLock();
			}
			catch (Exception e) {
				log.error("Could not release lock", e);
			}
			try {
				database.getConnection().close();
			}
			catch (Exception e) {
				//pass
			}
		}
		
		return updateWarnings;
	}
	
	/**
	 * Ask Liquibase if it needs to do any updates.
	 *
	 * @return true/false whether database updates are required
	 * @throws Exception when an exception is raised while processing Liquibase changelog files
	 */
	public static boolean updatesRequired() throws Exception {
		log.debug("checking for updates");
		List<OpenMRSChangeSet> changesets = getUnrunDatabaseChanges(new DatabaseUpdaterLiquibaseProvider());
		
		// if the db is locked, it means there was a crash
		// or someone is executing db updates right now. either way
		// returning true here stops the openmrs startup and shows
		// the user the maintenance wizard for updates
		if (isLocked() && changesets.isEmpty()) {
			// if there is a db lock but there are no db changes we undo the
			// lock
			DatabaseUpdater.releaseDatabaseLock();
			log.debug("db lock found and released automatically");
			return false;
		}
		
		return !changesets.isEmpty();
	}
	
	/**
	 * Ask Liquibase if it needs to do any updates
	 *
	 * @param changeLogFilenames the filenames of all files to search for unrun changesets
	 * @return true/false whether database updates are required <strong>Should</strong> always have a
	 *         valid update to latest file
	 */
	public static boolean updatesRequired(String... changeLogFilenames) throws Exception {
		log.debug("checking for updates");
		List<OpenMRSChangeSet> changesets = getUnrunDatabaseChanges(changeLogFilenames);
		return !changesets.isEmpty();
	}
	
	/**
	 * Indicates whether automatic database updates are allowed by this server. Automatic updates are
	 * disabled by default. In order to enable automatic updates, the admin needs to add
	 * 'auto_update_database=true' to the runtime properties file.
	 *
	 * @return true/false whether the 'auto_update_database' has been enabled.
	 */
	public static Boolean allowAutoUpdate() {
		String allowAutoUpdate = Context.getRuntimeProperties()
		        .getProperty(OpenmrsConstants.AUTO_UPDATE_DATABASE_RUNTIME_PROPERTY, "false");
		
		return "true".equals(allowAutoUpdate);
		
	}
	
	/**
	 * Takes the default properties defined in /metadata/api/hibernate/hibernate.default.properties and
	 * merges it into the user-defined runtime properties
	 *
	 * @see org.openmrs.api.db.ContextDAO#mergeDefaultRuntimeProperties(Properties)
	 */
	private static void mergeDefaultRuntimeProperties(Properties runtimeProperties) {
		
		// loop over runtime properties and precede each with "hibernate" if
		// it isn't already
		// must do it this way to prevent concurrent mod errors
		Set<Object> runtimePropertyKeys = new HashSet<>(runtimeProperties.keySet());
		for (Object key : runtimePropertyKeys) {
			String prop = (String) key;
			String value = (String) runtimeProperties.get(key);
			log.trace("Setting property: " + prop + ":" + value);
			if (!prop.startsWith("hibernate") && !runtimeProperties.containsKey("hibernate." + prop)) {
				runtimeProperties.setProperty("hibernate." + prop, value);
			}
		}
		
		// load in the default hibernate properties from hibernate.default.properties
		InputStream propertyStream = null;
		try {
			Properties props = new Properties();
			// TODO: This is a dumb requirement to have hibernate in here.  Clean this up
			propertyStream = DatabaseUpdater.class.getClassLoader().getResourceAsStream("hibernate.default.properties");
			OpenmrsUtil.loadProperties(props, propertyStream);
			// add in all default properties that don't exist in the runtime
			// properties yet
			for (Map.Entry<Object, Object> entry : props.entrySet()) {
				if (!runtimeProperties.containsKey(entry.getKey())) {
					runtimeProperties.put(entry.getKey(), entry.getValue());
				}
			}
		}
		finally {
			try {
				propertyStream.close();
			}
			catch (Exception e) {
				// pass
			}
		}
	}
	
	/**
	 * Exposes Liquibase instances created by this class. When calling
	 * org.openmrs.util.DatabaseUpdater#getInitialLiquibaseSnapshotVersion(LiquibaseProvider) and
	 * org.openmrs.util.DatabaseUpdater#getInitialLiquibaseSnapshotVersion(String,LiquibaseProvider), a
	 * Liquibase instance created by this class is injected into these methods. The Liquibase instance
	 * is injected into these methods instead of calling
	 * org.openmrs.util.DatabaseUpdater#getLiquibase(String,ClassLoader) directly. The reason for that
	 * design decision is that injecting a Liquibase instance (via a Liquibase provider) makes it
	 * possible to test the two methods mentioned above in isolation. The respective integration test is
	 * org.openmrs.util.DatabaseUpdateIT.
	 * 
	 * @see LiquibaseProvider
	 * @param changeLogFile name of a Liquibase change log file
	 * @return a Liquibase instance
	 * @throws Exception
	 */
	static Liquibase getLiquibase(String changeLogFile) throws Exception {
		return getLiquibase(changeLogFile, OpenmrsClassLoader.getInstance());
	}
	
	/**
	 * Get a connection to the database through Liquibase. The calling method /must/ close the database
	 * connection when finished with this Liquibase object.
	 * liquibase.getDatabase().getConnection().close()
	 *
	 * @param changeLogFile the name of the file to look for the on classpath or filesystem
	 * @param cl the {@link ClassLoader} to use to find the file (or null to use
	 *            {@link OpenmrsClassLoader})
	 * @return Liquibase object based on the current connection settings
	 * @throws Exception
	 */
	private static Liquibase getLiquibase(String changeLogFile, ClassLoader cl) throws Exception {
		Connection connection;
		try {
			connection = getConnection();
		}
		catch (SQLException e) {
			throw new Exception(
			        "Unable to get a connection to the database.  Please check your openmrs runtime properties file and make sure you have the correct connection.username and connection.password set",
			        e);
		}
		
		if (cl == null) {
			cl = OpenmrsClassLoader.getInstance();
		}
		
		try {
			Database database = DatabaseFactory.getInstance()
			        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
			database.setDatabaseChangeLogTableName("liquibasechangelog");
			database.setDatabaseChangeLogLockTableName("liquibasechangeloglock");
			
			if (connection.getMetaData().getDatabaseProductName().contains("HSQL Database Engine")
			        || connection.getMetaData().getDatabaseProductName().contains("H2")) {
				// a hack because hsqldb and h2 seem to be checking table names in the metadata section case sensitively
				database.setDatabaseChangeLogTableName(database.getDatabaseChangeLogTableName().toUpperCase());
				database.setDatabaseChangeLogLockTableName(database.getDatabaseChangeLogLockTableName().toUpperCase());
			}
			
			ResourceAccessor openmrsFO = new ClassLoaderFileOpener(cl);
			ResourceAccessor fsFO = new FileSystemResourceAccessor();
			
			if (changeLogFile == null) {
				changeLogFile = EMPTY_CHANGE_LOG_FILE;
			}

			// ensure that the change log history service is initialised
			//
			ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(database).init();
			
			return new Liquibase(changeLogFile, new CompositeResourceAccessor(openmrsFO, fsFO), database);
		}
		catch (Exception e) {
			// if an error occurs, close the connection
			if (connection != null) {
				connection.close();
			}
			throw e;
		}
	}
	
	/**
	 * Gets a database connection for liquibase to do the updates
	 *
	 * @return a java.sql.connection based on the current runtime properties
	 */
	public static Connection getConnection() throws Exception {
		Properties props = Context.getRuntimeProperties();
		mergeDefaultRuntimeProperties(props);
		
		String driver = props.getProperty("hibernate.connection.driver_class");
		String username = props.getProperty("hibernate.connection.username");
		String password = props.getProperty("hibernate.connection.password");
		String url = props.getProperty("hibernate.connection.url");
		
		// hack for mysql to make sure innodb tables are created
		if (url.contains("mysql") && !url.contains("InnoDB")) {
			url = url + "&sessionVariables=default_storage_engine=InnoDB";
		}
		
		Class.forName(driver);
		return DriverManager.getConnection(url, username, password);
	}
	
	/**
	 * Represents each change in the files referenced by liquibase-update-to-latest
	 */
	public static class OpenMRSChangeSet {
		
		private String id;
		
		private String author;
		
		private String comments;
		
		private String description;
		
		private ChangeSet.RunStatus runStatus;
		
		private Date ranDate;
		
		/**
		 * Create an OpenmrsChangeSet from the given changeset
		 *
		 * @param changeSet
		 * @param database
		 */
		public OpenMRSChangeSet(ChangeSet changeSet, Database database) throws Exception {
			setId(changeSet.getId());
			setAuthor(changeSet.getAuthor());
			setComments(changeSet.getComments());
			setDescription(changeSet.getDescription());
			setRunStatus(database.getRunStatus(changeSet));
			setRanDate(database.getRanDate(changeSet));
		}
		
		/**
		 * @return the author
		 */
		public String getAuthor() {
			return author;
		}
		
		/**
		 * @param author the author to set
		 */
		public void setAuthor(String author) {
			this.author = author;
		}
		
		/**
		 * @return the comments
		 */
		public String getComments() {
			return comments;
		}
		
		/**
		 * @param comments the comments to set
		 */
		public void setComments(String comments) {
			this.comments = comments;
		}
		
		/**
		 * @return the description
		 */
		public String getDescription() {
			return description;
		}
		
		/**
		 * @param description the description to set
		 */
		public void setDescription(String description) {
			this.description = description;
		}
		
		/**
		 * @return the runStatus
		 */
		public ChangeSet.RunStatus getRunStatus() {
			return runStatus;
		}
		
		/**
		 * @param runStatus the runStatus to set
		 */
		public void setRunStatus(ChangeSet.RunStatus runStatus) {
			this.runStatus = runStatus;
		}
		
		/**
		 * @return the ranDate
		 */
		public Date getRanDate() {
			return ranDate;
		}
		
		/**
		 * @param ranDate the ranDate to set
		 */
		public void setRanDate(Date ranDate) {
			this.ranDate = ranDate;
		}
		
		/**
		 * @return the id
		 */
		public String getId() {
			return id;
		}
		
		/**
		 * @param id the id to set
		 */
		public void setId(String id) {
			this.id = id;
		}
		
	}
	
	/**
	 * Returns a list of Liquibase change sets were not run yet.
	 *
	 * @param liquibaseProvider provides access to a Liquibase instance
	 * @return list of change sets that were not run yet.
	 */
	@Authorized(PrivilegeConstants.GET_DATABASE_CHANGES)
	public static List<OpenMRSChangeSet> getUnrunDatabaseChanges(LiquibaseProvider liquibaseProvider) throws Exception {
		String initialSnapshotVersion = changeLogDetective.getInitialLiquibaseSnapshotVersion(CONTEXT, liquibaseProvider);
		log.debug("initial snapshot version is '{}'", initialSnapshotVersion);
		
		List<String> liquibaseUpdateFilenames = changeLogDetective.getUnrunLiquibaseUpdateFileNames(initialSnapshotVersion,
		    CONTEXT, liquibaseProvider);
		
		if (liquibaseUpdateFilenames.size() > 0) {
			return getUnrunDatabaseChanges(liquibaseUpdateFilenames.toArray(new String[0]));
		}
		
		return new ArrayList<OpenMRSChangeSet>();
	}
	
	/**
	 * Looks at the specified liquibase change log files and returns all changesets in the files that
	 * have not been run on the database yet. If no argument is specified, then it looks at the current
	 * liquibase-update-to-latest.xml file
	 *
	 * @param changeLogFilenames the filenames of all files to search for unrun changesets
	 * @return list of change sets
	 */
	@Authorized(PrivilegeConstants.GET_DATABASE_CHANGES)
	public static List<OpenMRSChangeSet> getUnrunDatabaseChanges(String... changeLogFilenames) {
		log.debug("looking for un-run change sets in '{}'", Arrays.toString(changeLogFilenames));
		
		Database database = null;
		try {
			if (changeLogFilenames == null || changeLogFilenames.length == 0) {
				throw new IllegalArgumentException("changeLogFilenames can neither null nor an empty array");
			}
			
			List<OpenMRSChangeSet> results = new ArrayList<>();
			for (String changelogFile : changeLogFilenames) {
				Liquibase liquibase = getLiquibase(changelogFile, null);
				database = liquibase.getDatabase();
				
				List<ChangeSet> changeSets = liquibase.listUnrunChangeSets(new Contexts(CONTEXT), new LabelExpression());
				
				for (ChangeSet changeSet : changeSets) {
					OpenMRSChangeSet omrschangeset = new OpenMRSChangeSet(changeSet, database);
					results.add(omrschangeset);
				}
			}
			
			return results;
			
		}
		catch (Exception e) {
			throw new RuntimeException(
			        "Error occurred while trying to get the updates needed for the database. " + e.getMessage(), e);
		}
		finally {
			try {
				database.getConnection().close();
			}
			catch (Exception e) {
				//pass
			}
		}
	}
	
	/**
	 * @return the authenticatedUserId
	 */
	public static Integer getAuthenticatedUserId() {
		return authenticatedUserId;
	}
	
	/**
	 * @param userId the authenticatedUserId to set
	 */
	public static void setAuthenticatedUserId(Integer userId) {
		authenticatedUserId = userId;
	}
	
	/**
	 * This method is called by an executing custom changeset to register warning messages.
	 *
	 * @param warnings list of warnings to append to the end of the current list
	 */
	public static void reportUpdateWarnings(List<String> warnings) {
		if (updateWarnings == null) {
			updateWarnings = new LinkedList<>();
		}
		updateWarnings.addAll(warnings);
	}
	
	/**
	 * This method writes the given text to the database updates log file located in the application
	 * data directory.
	 *
	 * @param text text to be written to the file
	 */
	public static void writeUpdateMessagesToFile(String text) {
		OutputStreamWriter streamWriter = null;
		PrintWriter writer = null;
		File destFile = new File(OpenmrsUtil.getApplicationDataDirectory(), DatabaseUpdater.DATABASE_UPDATES_LOG_FILE);
		try {
			String lineSeparator = System.getProperty("line.separator");
			Date date = Calendar.getInstance().getTime();
			
			streamWriter = new OutputStreamWriter(new FileOutputStream(destFile, true), StandardCharsets.UTF_8);
			writer = new PrintWriter(new BufferedWriter(streamWriter));
			writer.write("********** START OF DATABASE UPDATE LOGS AS AT " + date + " **********");
			writer.write(lineSeparator);
			writer.write(lineSeparator);
			writer.write(text);
			writer.write(lineSeparator);
			writer.write(lineSeparator);
			writer.write("*********** END OF DATABASE UPDATE LOGS AS AT " + date + " ***********");
			writer.write(lineSeparator);
			writer.write(lineSeparator);
			
			//check if there was an error while writing to the file
			if (writer.checkError()) {
				log.warn("An Error occured while writing warnings to the database update log file'");
			}
			
			writer.close();
		}
		catch (FileNotFoundException e) {
			log.warn("Failed to find the database update log file", e);
		}
		catch (IOException e) {
			log.warn("Failed to write to the database update log file", e);
		}
		finally {
			IOUtils.closeQuietly(streamWriter);
			IOUtils.closeQuietly(writer);
		}
	}
	
	/**
	 * This method releases the liquibase db lock after a crashed database update. First, it checks
	 * whether "liquibasechangeloglock" table exists in db. If so, it will check whether the database is
	 * locked. If that is also true, this means that last attempted db update crashed.<br>
	 * <br>
	 * This should only be called if the user is sure that no one else is currently running database
	 * updates. This method should be used if there was a db crash while updates were being written and
	 * the lock table was never cleaned up.
	 *
	 * @throws LockException
	 */
	public static synchronized void releaseDatabaseLock() throws LockException {
		Database database = null;
		
		try {
			Liquibase liquibase = getLiquibase(null, null);
			database = liquibase.getDatabase();
			LockService lockService = LockServiceFactory.getInstance().getLockService(database);
			if (lockService.hasChangeLogLock() && isLocked()) {
				lockService.forceReleaseLock();
			}
		}
		catch (Exception e) {
			throw new LockException(e);
		}
		finally {
			try {
				database.getConnection().close();
			}
			catch (Exception e) {
				// pass
			}
		}
	}
	
	/**
	 * This method currently checks the liquibasechangeloglock table to see if there is a row with a
	 * lock in it. This uses the liquibase API to do this
	 *
	 * @return true if database is currently locked
	 */
	public static boolean isLocked() {
		Database database = null;
		try {
			Liquibase liquibase = getLiquibase(null, null);
			database = liquibase.getDatabase();
			return LockServiceFactory.getInstance().getLockService(database).listLocks().length > 0;
		}
		catch (Exception e) {
			return false;
		}
		finally {
			try {
				database.getConnection().close();
			}
			catch (Exception e) {
				// pass
			}
		}
	}
}
