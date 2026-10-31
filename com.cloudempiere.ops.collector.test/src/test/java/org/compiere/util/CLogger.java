/******************************************************************************
 * Test-only stand-in for iDempiere core's org.compiere.util.CLogger.          *
 *                                                                              *
 * This module is deliberately OSGi/iDempiere-runtime-free (see this module's  *
 * pom.xml), but EventDispatcher's static initializer calls                    *
 * CLogger.getCLogger(String) unconditionally - a bare ClassNotFoundException, *
 * not a behavioral dependency, since EventDispatcher's methods are mocked in  *
 * tests rather than actually invoked. This stub exists purely so that class   *
 * initialization succeeds; it is never meant to log anything for real.        *
 *****************************************************************************/
package org.compiere.util;

import java.util.logging.Logger;

public class CLogger extends Logger {

	protected CLogger(String name) {
		super(name, null);
	}

	public static CLogger getCLogger(String name) {
		return new CLogger(name);
	}

	public static CLogger getCLogger(Class<?> clazz) {
		return new CLogger(clazz.getName());
	}
}
