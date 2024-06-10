/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.scheduler.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.User;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.test.jupiter.BaseContextSensitiveTest;
import org.openmrs.util.OpenmrsConstants;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoRetireUsersTaskTest extends BaseContextSensitiveTest {
	private static final String XML_FILENAME = "org/openmrs/api/include/UserServiceTest.xml";
	private static final long ONE_DAY_IN_MILLISECONDS = 24 * 60 * 60 * 1000;
	private static final long TWENTY_THREE_HOURS_IN_MILLISECONDS = 23 * 60 * 60 * 1000;
	private static final long ONE_DAY_AND_TWO_SECONDS_IN_MILLISECONDS = ONE_DAY_IN_MILLISECONDS + 2000;
	private static final long TWO_DAYS_IN_MILLISECONDS = 2 * ONE_DAY_IN_MILLISECONDS;
	private static final String ONE_DAY_PROPERTY_VALUE = "1";
	private static final String TWO_DAYS_PROPERTY_VALUE = "2";

	private UserService userService;
	private AdministrationService administrationService;
	private AutoRetireUsersTask autoRetireUsersTask;

	@BeforeEach
	public void setup() {
		userService = Context.getUserService();
		administrationService = Context.getAdministrationService();
		autoRetireUsersTask = new AutoRetireUsersTask();
		executeDataSet(XML_FILENAME);
	}

	@Test
	public void getUsersToRetire_shouldReturnUsersThatExceedInactivityThreshold() {
		// Global property for number of days to retire
		administrationService.saveGlobalProperty(new GlobalProperty(OpenmrsConstants.GP_NUMBER_OF_DAYS_TO_AUTO_RETIRE_USERS, ONE_DAY_PROPERTY_VALUE));

		User inactiveUser = getDefaultUser();
		inactiveUser.setUserProperty(
			OpenmrsConstants.USER_PROPERTY_LAST_LOGIN_TIMESTAMP, 
			String.valueOf(System.currentTimeMillis() - TWO_DAYS_IN_MILLISECONDS)
		);

		userService.saveUser(inactiveUser);

		Set<User> usersToRetire = autoRetireUsersTask.getUsersToRetire(userService);

		assertThat(usersToRetire, contains(inactiveUser));
	}

	@Test
	public void getUsersToRetire_shouldNotReturnUsersThatDoNotExceedInactivityThreshold() {
		// Global property for number of days to retire
		administrationService.saveGlobalProperty(new GlobalProperty(OpenmrsConstants.GP_NUMBER_OF_DAYS_TO_AUTO_RETIRE_USERS, TWO_DAYS_PROPERTY_VALUE));

		User inactiveUser = getDefaultUser();
		inactiveUser.setUserProperty(
			OpenmrsConstants.USER_PROPERTY_LAST_LOGIN_TIMESTAMP, 
			String.valueOf(System.currentTimeMillis() - ONE_DAY_IN_MILLISECONDS)
		);

		userService.saveUser(inactiveUser);

		Set<User> usersToRetire = autoRetireUsersTask.getUsersToRetire(userService);

		assertThat(usersToRetire, not(contains(inactiveUser)));
	}

	@Test
	public void getUsersToRetire_shouldNotReturnAlreadyRetiredUsers() {
		// Global property for number of days to retire
		administrationService.saveGlobalProperty(new GlobalProperty(OpenmrsConstants.GP_NUMBER_OF_DAYS_TO_AUTO_RETIRE_USERS, ONE_DAY_PROPERTY_VALUE));

		User retiredUser = getDefaultUser();
		retiredUser.setUserProperty(
			OpenmrsConstants.USER_PROPERTY_LAST_LOGIN_TIMESTAMP, 
			String.valueOf(System.currentTimeMillis() - TWO_DAYS_IN_MILLISECONDS)
		); 
		
		retiredUser.setRetired(true);
		retiredUser.setRetireReason("User retired due to inactivity");

		userService.saveUser(retiredUser);

		Set<User> usersToRetire = autoRetireUsersTask.getUsersToRetire(userService);

		assertThat(usersToRetire, not(contains(retiredUser)));
	}

	@Test
	public void userInactivityExceedsThreshold_shouldReturnTrueIfInactivityExceedsThreshold() {
		User user = getDefaultUser();
		user.setUserProperty(
			OpenmrsConstants.USER_PROPERTY_LAST_LOGIN_TIMESTAMP, 
			String.valueOf(System.currentTimeMillis() - ONE_DAY_AND_TWO_SECONDS_IN_MILLISECONDS)
		);

		userService.saveUser(user);

		boolean result = autoRetireUsersTask.userInactivityExceedsDaysToRetire(user, ONE_DAY_IN_MILLISECONDS);
		assertTrue(result);
	}

	@Test
	public void userInactivityExceedsThreshold_shouldReturnFalseIfInactivityDoesNotExceedThreshold() {
		User user = getDefaultUser();
		user.setUserProperty(
			OpenmrsConstants.USER_PROPERTY_LAST_LOGIN_TIMESTAMP, 
			String.valueOf(System.currentTimeMillis() - TWENTY_THREE_HOURS_IN_MILLISECONDS)
		);

		userService.saveUser(user);

		boolean result = autoRetireUsersTask.userInactivityExceedsDaysToRetire(user, ONE_DAY_IN_MILLISECONDS);
		assertFalse(result);
	}

	private User getDefaultUser() {
		return userService.getUser(1);
	}
}
