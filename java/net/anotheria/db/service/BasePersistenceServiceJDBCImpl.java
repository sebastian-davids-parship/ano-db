package net.anotheria.db.service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.SocketException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.anotheria.db.config.JDBCConfig;
import net.anotheria.db.config.JDBCConfigFactory;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.log4j.Logger;

/**
 * Base persistence service.
 */
public abstract class BasePersistenceServiceJDBCImpl {

	/**
	 * Data source.
	 */
	private BasicDataSource dataSource;

	/**
	 * Logger.
	 */
	protected Logger log = Logger.getLogger(BasePersistenceServiceJDBCImpl.class.getClass());

	/**
	 * PROXY factory.
	 */
	private GenericReconnectionProxyFactory proxyFactory;

	/**
	 * Reconnection flag.
	 */
	private AtomicBoolean isBeingReconnected = new AtomicBoolean(false);

	/**
	 * Default constructor.
	 */
	protected BasePersistenceServiceJDBCImpl() {
		init();
		proxyFactory = new GenericReconnectionProxyFactory();
	}

	/**
	 * Initialize data source.
	 */
	public void init() {
		BasicDataSource newDataSource = new BasicDataSource();
		JDBCConfig config = JDBCConfigFactory.getJDBCConfig();
		log.info("Using config: " + config);
		newDataSource.setDriverClassName(config.getDriver());
		newDataSource.setUrl("jdbc:" + config.getVendor() + "://" + config.getHost() + ":" + config.getPort() + "/" + config.getDb());
		newDataSource.setUsername(config.getUsername());
		newDataSource.setPassword(config.getPassword());

		if (config.getMaxConnections() != Integer.MAX_VALUE)
			newDataSource.setMaxActive(config.getMaxConnections());

		this.dataSource = newDataSource;
	}

	/**
	 * Get connection from pool.
	 * 
	 * @return {@link Connection}
	 * @throws SQLException
	 */
	protected Connection getConnection() throws SQLException {
		if (isBeingReconnected.get())
			throw new JDBCConnectionException();

		try {
			return proxyFactory.getProxy(Connection.class, dataSource.getConnection());
		} catch (SQLException sqle) {
			handleJDBCConnectionException(sqle);
			throw sqle;
		}
	}

	/**
	 * Close {@link Connection} if opened. All {@link SQLException} on closing are ignored.
	 * 
	 * @param conn
	 *            - {@link Connection} object
	 */
	protected void close(Connection conn) {
		try {
			if (conn != null && !conn.isClosed()) {
				conn.close();
			}
		} catch (SQLException e) {
		}
	}

	/**
	 * Close {@link Statement} if opened. All {@link SQLException} on closing are ignored.
	 * 
	 * @param st
	 *            - {@link Statement} object
	 */
	protected void close(Statement st) {
		if (st != null) {
			try {
				st.close();
			} catch (SQLException e) {
			}
		}
	}

	/**
	 * Close {@link ResultSet} if opened. All {@link SQLException} on closing are ignored.
	 * 
	 * @param st
	 *            - {@link ResultSet} object
	 */
	protected void close(ResultSet rs) {
		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException e) {
			}
		}
	}

	/**
	 * Close {@link Connection} if opened. All {@link SQLException} on closing are ignored.
	 * 
	 * @param conn
	 *            - {@link Connection} object
	 */
	protected void release(Connection conn) {
		close(conn);
	}

	/**
	 * Close {@link Statement} if opened. All {@link SQLException} on closing are ignored.
	 * 
	 * @param st
	 *            - {@link Statement} object
	 */
	protected void release(Statement st) {
		close(st);
	}

	/**
	 * Close {@link ResultSet} if opened. All {@link SQLException} on closing are ignored.
	 * 
	 * @param st
	 *            - {@link ResultSet} object
	 */
	protected void release(ResultSet rs) {
		close(rs);
	}

	/**
	 * Check exception for connection exception type and throw named runtime exception.
	 * 
	 * @param error
	 *            - {@link Throwable}
	 * @throws JDBCConnectionException
	 */
	private void handleJDBCConnectionException(Throwable error) throws JDBCConnectionException {
		if (error instanceof SocketException || error instanceof ConnectException) {
			isBeingReconnected.set(true);
			try {
				init();
			} finally {
				isBeingReconnected.set(false);
			}
			throw new JDBCConnectionException();
		}

		if (error.getCause() != null)
			handleJDBCConnectionException(error.getCause());
	}

	/**
	 * Factory for creating PROXY for some JDBC layer implementations for handling JDBC connection exceptions and reloading data source.
	 */
	private class GenericReconnectionProxyFactory {

		public static final String CREATE_STATEMENT = "createStatement";
		public static final String PREPARE_STATEMENT = "prepareStatement";
		public static final String PREPARE_CALL = "prepareCall";
		public static final String META_DATA = "getMetaData";
		public static final String CLOSE = "close";

		public final Set<String> methodNames = new HashSet<String>();
		public final Set<String> classNames = new HashSet<String>();

		public GenericReconnectionProxyFactory() {
			methodNames.add(CREATE_STATEMENT);
			methodNames.add(PREPARE_STATEMENT);
			methodNames.add(PREPARE_CALL);
			methodNames.add(META_DATA);
			methodNames.add(CLOSE);

			classNames.add(Connection.class.getName());
			classNames.add(DatabaseMetaData.class.getName());
			classNames.add(Statement.class.getName());
			classNames.add(PreparedStatement.class.getName());
			classNames.add(CallableStatement.class.getName());
			classNames.add(ResultSet.class.getName());
		}

		/**
		 * Make PROXY.
		 * 
		 * @param <T>
		 * @param intf
		 * @param obj
		 * @return
		 */

		public <T> T getProxy(Class<T> intf, final T obj) {

			return intf.cast(Proxy.newProxyInstance(obj.getClass().getClassLoader(), new Class[] { intf }, new InvocationHandler() {

				public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
					if (isBeingReconnected.get())
						throw new JDBCConnectionException();

					try {
						if (methodNames.contains(method.getName()) && classNames.contains(method.getDeclaringClass().getName()))
							return proxyFactory.getProxy((Class<T>) method.getDeclaringClass(), (T) method.invoke(obj, args));

						return method.invoke(obj, args);
					} catch (InvocationTargetException e) {
						handleJDBCConnectionException(e);

						throw e;
					}
				}
			}));

		}
	}

}
