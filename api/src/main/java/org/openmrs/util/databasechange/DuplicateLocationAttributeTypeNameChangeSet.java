/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.util.databasechange;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.DatabaseException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.util.DatabaseUpdater;
import org.openmrs.util.DatabaseUtil;

/**
 * Liquibase custom changeset used to identify and resolve duplicate LocationAttributeType names. If a
 * duplicate LocationAttributeType name is identified, it will be edited to include a suffix term which
 * makes it unique, and identifies it as a value to be manually changed during later review
 */

public class DuplicateLocationAttributeTypeNameChangeSet implements CustomTaskChange {
	
	private static final Log log = LogFactory.getLog(DuplicateLocationAttributeTypeNameChangeSet.class);
	
	@Override
	public String getConfirmationMessage() {
		return "Completed updating duplicate LocationAttributeType names";
	}
	
	@Override
	public void setFileOpener(ResourceAccessor arg0) {
		
	}
	
	@Override
	public void setUp() throws SetupException {
		// No setup actions
	}
	
	@Override
	public ValidationErrors validate(Database arg0) {
		return null;
	}
	
	/**
	 * Method to perform validation and resolution of duplicate LocationAttributeType names
	 */
	@Override
	public void execute(Database database) throws CustomChangeException {
		JdbcConnection connection = (JdbcConnection) database.getConnection();
		Map<String, HashSet<Integer>> duplicates = new HashMap<String, HashSet<Integer>>();
		Statement stmt = null;
		PreparedStatement pStmt = null;
		ResultSet rs = null;
		boolean autoCommit = true;
		try {
			// set auto commit mode to false for UPDATE action
			autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			stmt = connection.createStatement();
			rs = stmt.executeQuery("SELECT * FROM location_attribute_type "
			        + "INNER JOIN (SELECT name FROM location_attribute_type GROUP BY name HAVING count(name) > 1) "
			        + "dup ON location_attribute_type.name = dup.name");
			Integer id = null;
			String name = null;
			
			while (rs.next()) {
				id = rs.getInt("location_attribute_type_id");
				name = rs.getString("name");
				if (duplicates.get(name) == null) {
					HashSet<Integer> results = new HashSet<Integer>();
					results.add(id);
					duplicates.put(name, results);
				} else {
					HashSet<Integer> results = duplicates.get(name);
					results.add(id);
				}
			}
			
			Iterator it2 = duplicates.entrySet().iterator();
			while (it2.hasNext()) {
				Map.Entry pairs = (Map.Entry) it2.next();
				HashSet values = (HashSet) pairs.getValue();
				List<Integer> duplicateNames = new ArrayList<Integer>(values);
				int duplicateNameId = 1;
				for (int i = 1; i < duplicateNames.size(); i++) {
					String newName = pairs.getKey() + "_" + duplicateNameId;
					List<List<Object>> duplicateResult = null;
					boolean duplicateName = false;
					Connection con = DatabaseUpdater.getConnection();
					do {
						String sqlValidatorString = "select * from location_attribute_type where name = '" + newName + "'";
						duplicateResult = DatabaseUtil.executeSQL(con, sqlValidatorString, true);
						if (!duplicateResult.isEmpty()) {
							duplicateNameId += 1;
							newName = pairs.getKey() + "_" + duplicateNameId;
							duplicateName = true;
						} else {
							duplicateName = false;
						}
					} while (duplicateName);
					pStmt = connection
					        .prepareStatement("update location_attribute_type set name = ?, changed_by = ?, date_changed = ? where location_attribute_type_id = ?");
					if (!duplicateResult.isEmpty()) {
						pStmt.setString(1, newName);
					}
					pStmt.setString(1, newName);
					pStmt.setInt(2, DatabaseUpdater.getAuthenticatedUserId());
					
					Calendar cal = Calendar.getInstance();
					Date date = new Date(cal.getTimeInMillis());
					
					pStmt.setDate(3, date);
					pStmt.setInt(4, duplicateNames.get(i));
					duplicateNameId += 1;
					
					pStmt.executeUpdate();
				}
			}
		}
		catch (BatchUpdateException e) {
			log.warn("Error generated while processsing batch insert", e);
			try {
				log.debug("Rolling back batch", e);
				connection.rollback();
			}
			catch (Exception rbe) {
				log.warn("Error generated while rolling back batch insert", e);
			}
			// marks the changeset as a failed one
			throw new CustomChangeException("Failed to update one or more duplicate LocationAttributeType names", e);
		}
		catch (Exception e) {
			throw new CustomChangeException("Error while updating duplicate LocationAttributeType object names", e);
		}
		finally {
			// reset to auto commit mode
			try {
				connection.commit();
				connection.setAutoCommit(autoCommit);
			}
			catch (DatabaseException e) {
				log.warn("Failed to reset auto commit back to true", e);
			}
			
			if (rs != null) {
				try {
					rs.close();
				}
				catch (SQLException e) {
					log.warn("Failed to close the resultset object");
				}
			}
			
			if (stmt != null) {
				try {
					stmt.close();
				}
				catch (SQLException e) {
					log
					        .warn("Failed to close the select statement used to identify duplicate LocationAttributeType object names");
				}
			}
			
			if (pStmt != null) {
				try {
					pStmt.close();
				}
				catch (SQLException e) {
					log
					        .warn("Failed to close the prepared statement used to update duplicate LocationAttributeType object names");
				}
			}
		}
	}
}
