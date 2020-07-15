/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 * <p>
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.APIException;

import javax.persistence.Column;
import javax.persistence.MappedSuperclass;

/**
 * This is a super class to make a bridge between an OpenMrsObject and is position in the form.
 * 
 * @since 1.4
 */
@MappedSuperclass
public abstract class BaseFormRecordableOpenmrsData extends BaseChangeableOpenmrsData implements FormRecordable {

	private static final String FORM_NAMESPACE_PATH_SEPARATOR = "^";

	private static final int FORM_NAMESPACE_PATH_MAX_LENGTH = 255;

	@Column(name = "form_namespace_path")
	protected String formNamespaceAndPath;

	/**
	 * @see org.openmrs.FormRecordable#getFormFieldNamespace()
	 */
	@Override
	public String getFormFieldNamespace() {
		if (StringUtils.isNotBlank(formNamespaceAndPath)) {
			//Only the path was specified
			if (formNamespaceAndPath.startsWith(FORM_NAMESPACE_PATH_SEPARATOR)) {
				return null;
			}
			return formNamespaceAndPath.substring(0, formNamespaceAndPath.indexOf(FORM_NAMESPACE_PATH_SEPARATOR));
		}

		return formNamespaceAndPath;
	}

	/**
	 * @see org.openmrs.FormRecordable#getFormFieldPath()
	 */
	@Override
	public String getFormFieldPath() {
		if (StringUtils.isNotBlank(formNamespaceAndPath)) {
			//Only the namespace was specified
			if (formNamespaceAndPath.endsWith(FORM_NAMESPACE_PATH_SEPARATOR)) {
				return null;
			}
			return formNamespaceAndPath.substring(formNamespaceAndPath.indexOf(FORM_NAMESPACE_PATH_SEPARATOR) + 1);
		}

		return formNamespaceAndPath;
	}

	/**
	 * @see org.openmrs.FormRecordable#setFormField(String,String)
	 */
	@Override
	public void setFormField(String namespace, String formFieldPath) {
		if (namespace == null && formFieldPath == null) {
			markAsDirty(formNamespaceAndPath, null);
			formNamespaceAndPath = null;
			return;
		}

		String nsAndPathTemp = "";
		if (StringUtils.isNotBlank(namespace) && StringUtils.isNotBlank(formFieldPath)) {
			nsAndPathTemp = namespace + FORM_NAMESPACE_PATH_SEPARATOR + formFieldPath;
		} else if (StringUtils.isNotBlank(namespace)) {
			nsAndPathTemp = namespace + FORM_NAMESPACE_PATH_SEPARATOR;
		} else if (StringUtils.isNotBlank(formFieldPath)) {
			nsAndPathTemp = FORM_NAMESPACE_PATH_SEPARATOR + formFieldPath;
		}

		if (nsAndPathTemp.length() > FORM_NAMESPACE_PATH_MAX_LENGTH) {
			throw new APIException("Obs.namespaceAndPathTooLong", (Object[]) null);
		}
		if (StringUtils.countMatches(nsAndPathTemp, FORM_NAMESPACE_PATH_SEPARATOR) > 1) {
			throw new APIException("Obs.namespaceAndPathNotContainSeparator", (Object[]) null);
		}

		markAsDirty(this.formNamespaceAndPath, nsAndPathTemp);
		formNamespaceAndPath = nsAndPathTemp;
	}

	protected abstract void markAsDirty(Object oldValue, Object newValue);

	/**
	 * @return Returns the formNamespaceAndPath.
	 */
	public String getFormNamespaceAndPath() {
		return formNamespaceAndPath;
	}
}
