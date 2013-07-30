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
 * Copyright (C) OpenMRS, LLC. All Rights Reserved.
 */
package org.openmrs.validator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.ConceptNameTag;
import org.openmrs.annotation.Handler;
import org.openmrs.api.context.Context;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;

/**
 * Validates attributes on the {@link ConceptNameTag} object.
 * 
 * @since 1.10
 */
@Handler(supports = { ConceptNameTag.class }, order = 50)
public class ConceptNameTagValidator implements Validator {
	
	/** Log for this class and subclasses */
	protected final Log log = LogFactory.getLog(getClass());
	
	/**
	 * Determines if the command object being submitted is a valid type
	 * 
	 * @see org.springframework.validation.Validator#supports(java.lang.Class)
	 */
	@SuppressWarnings("unchecked")
	public boolean supports(Class c) {
		return ConceptNameTag.class.isAssignableFrom(c);
	}
	
	/**
	 * Checks the form object for any inconsistencies/errors
	 * 
	 * @see org.springframework.validation.Validator#validate(java.lang.Object,
	 *      org.springframework.validation.Errors)
	 * @should fail validation if tag is null or empty or whitespace
	 * @should pass validation if description is null or empty or whitespace
	 * @should pass validation if all required fields have proper values
	 */
	
	public void validate(Object obj, Errors errors) {
		ConceptNameTag cnt = (ConceptNameTag) obj;
		if (cnt == null) {
			errors.rejectValue("conceptNameTag", "error.general");
		} else {
			ValidationUtils.rejectIfEmptyOrWhitespace(errors, "tag", "error.name");
			ValidationUtils.rejectIfEmptyOrWhitespace(errors, "description", "error.description");
			
			if (cnt.getTag() != null) {
				for (ConceptNameTag currentTag : Context.getConceptService().getAllConceptNameTags()) {
					if (currentTag.getTag().trim() == (cnt.getTag().trim())) {
						errors.rejectValue("tag", "conceptsearch.error.duplicate");
					}
				}
			}
		}
	}
}
