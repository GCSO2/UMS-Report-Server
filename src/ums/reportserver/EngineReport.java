package ums.reportserver;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ums.database.server.DataBaseConnectionServer;
import ums.exceptions.NotFoundParameterException;
import ums.logger.Logger;
import ums.logger.Messages;
import ums.reports.engine.UMSEngineReport;
import ums.reports.engine.UMSReportResult;
import ums.util.ParameterReader;
import ums.util.UtilDataBase;

/**
 * Servlet implementation class EngineReport
 */
public class EngineReport extends HttpServlet  {

	private static final long serialVersionUID = 1L;

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public EngineReport() {
		super();
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		System.setProperty("config.file", config.getInitParameter("config.file"));
		System.setProperty("log_dir", config.getInitParameter("log_dir"));
		System.setProperty("message_dir", config.getInitParameter("message_dir"));
	}

	private void writeOnLog(HttpServletRequest request, String message) {
		Logger.logInfo("User '" + request.getRemoteAddr() + "' " + message, null);
	}

	private void execute(HttpServletRequest request, HttpServletResponse response) throws IOException {
		Connection conn = null;
		try {

			writeOnLog(request, "accessing...");

			/* Used for Report */
			String token = request.getParameter("t");
			boolean report;
			if (token == null) {
				report = false;
				/* Used for Export CSV */
				token = request.getParameter("e");
			} else {
				report = true;
			}
			if (token == null || token.trim().equalsIgnoreCase("")) {
				reportError(request, response, "Request parameter 't' (Used for the Reports) or 'e' (Used for Export to CSV) not found: " + request.getLocalAddr() + ":[port]"
						+ request.getRequestURI());
				return;
			}

			/* Fixed the token */
			token = token.replace(" ", "+");

			/* Connect to Database */
			conn = DataBaseConnectionServer.getConnection();

			if (report) {
				executeJasperReport(request, response, conn, token);
				writeOnLog(request, "Report Successfully performed");
			} else {
				executeExportToCSV(request, response, conn, token);
				writeOnLog(request, "Export to CVS Successfully performed");
			}

		} catch (Throwable e) {
			reportError(e, response);
		} finally {
			DataBaseConnectionServer.destroySingleConnection(null);
		}
	}

	private void reportError(Throwable e, HttpServletResponse response) {
		try {
			Logger.logError(e, null);
			response.setContentType("text/html");
			response.setHeader("Cache-Control", "no-store"); // HTTP 1.1
			PrintWriter out = response.getWriter();
			out.println("<html>");
			out.println("<head>");
			out.println("<title>Access Denied</title>");
			out.println("</head>");
			out.println("<body>");
			if (e instanceof Exception) {
				Exception ee = (Exception) e;
				if (ee instanceof NotFoundParameterException) {
					out.println("<h1>" + Messages.getMessage("error.enginereport.general", true) + "</h1>");
				} else {
					out.println("<h1>" + Messages.getMessage("error.enginereport.accessdenied", true) + "</h1>");
				}
			} else {
				out.println("<h1>" + Messages.getMessage("error.enginereport.accessdenied", true) + "</h1>");
			}
			out.println("</body>");
			out.println("</html>");
		} catch (IOException e1) {
			Logger.logError(e1, null);
		}
	}

	private void reportError(HttpServletRequest request, HttpServletResponse response, String serverMessage) {
		try {
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			out.println("<html>");
			out.println("<head>");
			out.println("<title>Access Denied</title>");
			out.println("</head>");
			out.println("<body>");
			out.println("<h1>" + Messages.getMessage("error.enginereport.accessdenied", true) + "</h1>");
			out.println("</body>");
			out.println("</html>");
			Logger.logError(serverMessage, null);
		} catch (IOException e1) {
			Logger.logError(e1, null);
		}
	}

	private void executeJasperReport(HttpServletRequest request, HttpServletResponse response, Connection conn, String token) throws Throwable {

		/* Reading parameters from Database */
		String[] parametersArray = getParametersFromDatabase(token, conn);

		String parameters = parametersArray[0];
		String userName = parametersArray[2];

		/* File Name */
		String FILE_NAME = ParameterReader.getNewParameter("FILE_NAME", parameters, true);

		/* Report Engine */
		UMSEngineReport engineReport = new UMSEngineReport(conn, parameters);
		UMSReportResult reportResult = engineReport.executeReport();
		response.setContentType(reportResult.getContentType().content());

		writeOnLog(request, "USERNAME: '" + userName + "', REPORT_CODE: '" + reportResult.getREPORT_CODE() + "', OUTPUT_FORMAT: '" + reportResult.getOUTPUT_FORMAT() + "'");

		String attachedStr;
		if (reportResult.getContentType().isAttached()) {
			attachedStr = "attachment";
		} else {
			attachedStr = "inline";
		}

		response.setHeader("Content-Disposition", attachedStr + "; filename=\"" + FILE_NAME + "\";");

		/* Setup Stream */
		ServletOutputStream servletOutputStream = response.getOutputStream();
		byte[] bytes = reportResult.getStream();
		response.setContentLength(bytes.length);
		servletOutputStream.write(bytes, 0, bytes.length);
		servletOutputStream.flush();
		servletOutputStream.close();

	}

	private String[] getParametersFromDatabase(String token, Connection connection) throws Throwable {
		PreparedStatement ps = null;
		ResultSet rs = null;
		String[] parameters = new String[3];
		try {
			String SQL = "SELECT parameters,parameters2,last_insert_username FROM sys_data_exchange WHERE auth_token = ? AND now() < expire_timestamp ORDER BY last_insert_date DESC limit 1";
			ps = connection.prepareStatement(SQL);
			ps.setString(1, token);
			rs = ps.executeQuery();
			if (rs.next()) {
				parameters[0] = rs.getString("parameters");
				parameters[1] = rs.getString("parameters2");
				parameters[2] = rs.getString("last_insert_username");
				return parameters;
			}
			throw new RuntimeException("Record not found on database, when trying to read Data Exchange: (Token used:" + token + ") " + SQL);
		} catch (Throwable e) {
			Logger.logError(e, null);
			throw e;
		} finally {
			UtilDataBase.closeQuietly(ps, rs, null);
		}
	}

	private void executeExportToCSV(HttpServletRequest request, HttpServletResponse response, Connection connection, String token) throws Throwable {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {

			/* Reading parameters from Database */
			String[] parametersArray = getParametersFromDatabase(token, connection);

			String parameters = parametersArray[0];
			String parameters2 = parametersArray[1];
			String userName = parametersArray[2];

			String sql = ParameterReader.getNewParameter("SQL", parameters, true);
			String sqlParams = ParameterReader.getNewParameter("SQLPARAMS", parameters, false);

			String fileName = ParameterReader.getNewParameter("TITLE", parameters2, true);
			String columns = ParameterReader.getNewParameter("COLUMNS", parameters2, true);
			String USER_ID = ParameterReader.getNewParameter("USER_ID", parameters, true);
			String CHECKTIMEZONE = ParameterReader.getNewParameter("CHECKTIMEZONE", parameters, true);

			writeOnLog(request, "Export to CSV - USERNAME: '" + userName + "', TITLE: '" + fileName + "'");

			/* Set up the Stream */
			response.setContentType("application/octet-stream");
			response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + ".csv\"");

			StringTokenizer stColumns = new StringTokenizer(columns, ParameterReader.PARAMETER_INTERNAL_SEPARATOR);

			ps = connection.prepareStatement(sql);

			/* If exists any parameter */
			if (sqlParams != null) {
				StringTokenizer stParamsValues = new StringTokenizer(sqlParams, ParameterReader.PARAMETER_INTERNAL_SEPARATOR);

				for (int i = 1; stParamsValues.hasMoreElements(); i++) {
					ps.setObject(i, stParamsValues.nextElement());
				}
			}

			rs = ps.executeQuery();

			StringBuilder sb = new StringBuilder();

			/* Read just columns selected */
			List<String> columnsNameList = new ArrayList<String>();
			for (int i = 1; stColumns.hasMoreElements(); i++) {
				Object columnAndName = stColumns.nextElement();
				StringTokenizer stColumnAndName = new StringTokenizer((String) columnAndName, ParameterReader.PARAMETER_INTERNAL_EQUAL);
				String columnName = (String) stColumnAndName.nextElement();

				columnsNameList.add(columnName);
				String columnHeader = (String) stColumnAndName.nextElement();
				sb.append("\"");
				sb.append(columnHeader);
				sb.append("\",");
			}

			sb.append('\n');
			while (rs.next()) {
				for (String columnName : columnsNameList) {
					sb.append("\"");
					Object value = rs.getObject(columnName);
					value = checkDateValue(value, columnName, connection, CHECKTIMEZONE, USER_ID);
					sb.append(value == null ? "" : value);
					sb.append("\",");
				}
				sb.append('\n');
			}

			PrintWriter pw = response.getWriter();
			pw.append(sb.toString());
			pw.flush();
			pw.close();

		} catch (Exception e) {
			Logger.logError(e, null);
			throw e;
		} finally {
			UtilDataBase.closeQuietly(ps, rs, null);
		}
	}

	private void removeDataExchange(Long dataExchangeId, Connection connection) {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = connection.prepareStatement("DELETE FROM sys_data_exchange WHERE datexc_id = ?");
			ps.setObject(1, dataExchangeId);
			boolean removed = ps.execute();
			if (removed) {
				System.out.println("Data Exchange Removed: " + dataExchangeId);
			}
		} catch (SQLException e) {
			Logger.logError(e, null);
		} finally {
			UtilDataBase.closeQuietly(ps, rs, null);
		}

	}

	private Object checkDateValue(Object value, String columnName, Connection connection, String checkTimeZone, String use_id) {
		// /* Field Date */
		if (columnName.indexOf("_date") == -1 || value == null || checkTimeZone.equalsIgnoreCase("N")) {
			return value;
		}
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = connection.prepareStatement("SELECT date_format(convert_date_from_GMT_to_local_tz(?,?), '%b %d, %Y %r')  date_value");
			ps.setObject(1, use_id);
			ps.setObject(2, value);

			rs = ps.executeQuery();
			if (rs.next()) {
				return rs.getString("date_value");
			}
		} catch (SQLException e) {
			Logger.logError(e, null);
		} finally {
			UtilDataBase.closeQuietly(ps, rs, null);
		}
		return null;

	}

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		execute(request, response);
	}

}
