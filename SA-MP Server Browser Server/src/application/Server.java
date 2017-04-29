package application;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

import data.MySQLConnection;
import entities.SampServerSerializeable;
import entities.SampServerSerializeableBuilder;
import interfaces.DataServiceInterface;
import query.SampQuery;
import serviceImplementations.DataServiceServerImplementation;

public class Server
{
	private static final Logger logger = Logger.getLogger("Server");

	private static Registry registry;

	private static DataServiceInterface dataService;

	private static DataServiceInterface stub;

	public static void main(final String[] args)
	{

		if (args.length == 0)
		{
			System.out.println("Usage: java -jar Server.jar -u/-username [NAME]");
			System.out.println("Optionally, append a -recreatedb/-updatedb to force updating the server list.");
		}
		else if (args.length >= 1)
		{
			boolean recreatedb = false;
			String username = null;

			for (int i = 0; i < args.length; i++)
			{
				if (args[i].equals("-recreatedb") || args[i].equals("-updatedb"))
				{
					recreatedb = true;
				}
				else if (args[i].equals("-u") || args[i].equals("-username"))
				{
					if (args.length >= i + 2)
					{
						username = args[i + 1];
					}
					else
					{
						System.out.println("You must enter a username to access the MySQL database.");
						System.exit(0);
					}
				}
			}

			final Console console = System.console();
			final String password = new String(console.readPassword("Enter your database password: "));

			if (Objects.isNull(username))
			{
				System.out.println("Please enter a Username for your Database");
				System.exit(0);
			}

			MySQLConnection.init(username, password);

			try
			{
				registry = LocateRegistry.createRegistry(1099);
				dataService = new DataServiceServerImplementation();
				stub = (DataServiceInterface) UnicastRemoteObject.exportObject(dataService, 0);
				registry.rebind(DataServiceInterface.INTERFACE_NAME, stub);
				logger.log(Level.INFO, "RMI has been initialized.");
			}
			catch (final Exception e)
			{
				logger.log(Level.SEVERE, "Couldn't initialize RMI", e);
				System.exit(0);
			}

			if (recreatedb)
			{
				updateDBWithMasterlistContent();
			}

			setServerList();

			createCronJob();
		}

	}

	private static void setServerList()
	{
		try
		{
			DataServiceServerImplementation.clearList();

			final Statement statement = MySQLConnection.connect.createStatement();

			// TODO(MSC) Convert field in database instead of casting.
			final ResultSet resultSet = statement
					.executeQuery("SELECT version, ip_address, id, CONVERT(port, SIGNED INTEGER) as portNumber, hostname, players, lagcomp, max_players, mode, version, weburl, language FROM internet_offline;");

			final List<SampServerSerializeable> servers = new ArrayList<>();

			while (resultSet.next())
			{
				final SampServerSerializeable server = new SampServerSerializeableBuilder()
						.setHostname(resultSet.getString("hostname"))
						.setAddress(resultSet.getString("ip_address"))
						.setPort(resultSet.getInt("portNumber"))
						.setPlayers(resultSet.getInt("players"))
						.setMaxPlayers(resultSet.getInt("max_players"))
						.setMode(resultSet.getString("mode"))
						.setLanguage(resultSet.getString("language"))
						.setLagcomp(resultSet.getString("lagcomp"))
						.setWebsite(resultSet.getString("weburl"))
						.setVersion(resultSet.getString("version"))
						.build();

				if (!servers.contains(server))
				{
					servers.add(server);
				}
			}

			DataServiceServerImplementation.addToServers(servers);

		}
		catch (final SQLException e)
		{
			e.printStackTrace();
		}
	}

	private static void createCronJob()
	{
		try
		{
			final Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
			scheduler.start();

			class UpdateJob implements Job
			{
				@Override
				public void execute(@SuppressWarnings("unused") final JobExecutionContext arg0) throws JobExecutionException
				{
					updateDBWithMasterlistContent();
				}
			}

			final JobDetail job = JobBuilder.newJob(UpdateJob.class).withIdentity("updateList", "updater").build();

			final Trigger trigger = TriggerBuilder.newTrigger().withIdentity("updateTrigger", "updater").startNow()
					.withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(23, 59)).build();

			scheduler.scheduleJob(job, trigger);
		}
		catch (final SchedulerException e)
		{
			e.printStackTrace();
		}
	}

	private static void updateDBWithMasterlistContent()
	{
		logger.log(Level.INFO, "Starting to update Database.");

		if (MySQLConnection.truncate())
		{
			addToDatabaseFromList("http://monitor.sacnr.com/list/masterlist.txt");
			addToDatabaseFromList("http://monitor.sacnr.com/list/hostedlist.txt");
			setServerList();
		}

		logger.log(Level.INFO, "Database update is over, check past message to see if it was successful.");
	}

	private static void addToDatabaseFromList(final String url)
	{
		try
		{
			final URLConnection openConnection = new URL(url).openConnection();
			openConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:25.0) Gecko/20100101 Firefox/25.0");
			try (final BufferedReader in = new BufferedReader(new InputStreamReader(openConnection.getInputStream())))
			{
				String inputLine;
				while ((inputLine = in.readLine()) != null)
				{
					final String[] data = inputLine.split("[:]");

					try (final SampQuery query = new SampQuery(data[0], Integer.parseInt(data[1])))
					{
						final Optional<String[]> infoOptional = query.getBasicServerInfo();

						final Optional<String[][]> infoMoreOptional = query.getServersRules();

						if (infoOptional.isPresent() && infoMoreOptional.isPresent())
						{

							final String[] info = infoOptional.get();
							final String[][] infoMore = infoMoreOptional.get();

							String weburl = null;
							String lagcomp = null;
							String worldtime = null;
							String version = null;
							String map = null;
							int weather = 0;

							// TODO(MSC) Inspect data response of all server versions and remove
							// loops if possible
							for (final String[] element : infoMore)
							{
								if (element[0].equals("lagcomp"))
								{
									lagcomp = element[1];
								}
								else if (element[0].equals("weburl"))
								{
									weburl = element[1];
								}
								else if (element[0].equals("version"))
								{
									version = element[1];
								}
								else if (element[0].equals("worldtime"))
								{
									worldtime = element[1];
								}
								else if (element[0].equals("weather"))
								{
									weather = Integer.parseInt(element[1]);
								}
								else if (element[0].equals("mapname"))
								{
									map = element[1];
								}
							}

							MySQLConnection.addServer(data[0], data[1], info[3], Integer.parseInt(info[1]), Integer
									.parseInt(info[2]), info[4], info[5], lagcomp, map, version, weather, weburl, worldtime);
							logger.log(Level.INFO, "Added Server: " + inputLine);
						}
					}
					catch (final Exception e)
					{
						logger.log(Level.SEVERE, "Failed to connect to Server: " + inputLine);
					}
				}
			}
		}
		catch (

		final IOException e)
		{
			logger.log(Level.SEVERE, "Failed to add data from server lists.", e);
		}
	}
}
