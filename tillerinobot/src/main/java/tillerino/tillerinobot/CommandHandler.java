package tillerino.tillerinobot;

import static org.apache.commons.lang3.StringUtils.getLevenshteinDistance;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.commons.lang3.StringUtils;
import org.tillerino.osuApiModel.OsuApiUser;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;
import tillerino.tillerinobot.UserDataManager.UserData;
import tillerino.tillerinobot.data.util.ThreadLocalAutoCommittingEntityManager;

public interface CommandHandler {
	/**
	 * Response sent to the user as the result of a command
	 */
	interface Response {
		/**
		 * Adds another response to the current one.
		 */
		default Response then(@CheckForNull Response nextResponse) {
			ResponseList list = new ResponseList();
			if (this instanceof NoResponse) {
				return nextResponse;
			}
			if (nextResponse instanceof NoResponse || nextResponse == null) {
				return this;
			}
			if (this instanceof ResponseList) {
				list.responses.addAll(((ResponseList) this).responses);
			} else {
				list.responses.add(this);
			}
			if (nextResponse instanceof ResponseList) {
				list.responses.addAll(((ResponseList) nextResponse).responses);
			} else {
				list.responses.add(nextResponse);
			}
			return list;
		}

		/**
		 * Executes a task after this response.
		 */
		default Response thenRun(Task task) {
			return then(task);
		}

		/**
		 * Submits a task to the maintenance executor service after this response
		 * was handled.
		 */
		default Response thenRunAsync(AsyncTask task) {
			return then(task);
		}

		/**
		 * Chains this response with a clean up task. A clean up task will run
		 * even if one of the previous responses caused an exception.
		 */
		default Response thenCleanUp(CleanUpTask task) {
			return then(task);
		}

		static NoResponse none() {
			return new NoResponse();
		}

		/**
		 * @return true, this response or a child response sends something through IRC
		 */
		boolean sends();
	}

	/**
	 * A regular IRC message. This should not be used as the direct response to
	 * a command, but for other auxiliary messages, see {@link Success}.
	 */
	@Value
	public static class Message implements Response {
		String content;

		@Override
		public boolean sends() {
			return true;
		}
	}

	/**
	 * A regular IRC message, which will be logged as a successfully executed command.
	 * This is the message that the command duration will be logged for.
	 */
	@Value
	public static class Success implements Response {
		String content;

		@Override
		public boolean sends() {
			return true;
		}
	}

	/**
	 * An "action" type IRC message
	 */
	@Value
	public static class Action implements Response {
		String content;

		@Override
		public boolean sends() {
			return true;
		}
	}

	/**
	 * Returned by the handler to clarify that the command was handled, but no
	 * response is sent.
	 */
	@EqualsAndHashCode
	public static final class NoResponse implements Response {
		@Override
		public String toString() {
			return "[No Response]";
		}

		@Override
		public boolean sends() {
			return false;
		}
	}

	@EqualsAndHashCode
	@ToString
	public static final class ResponseList implements Response {
		List<Response> responses = new ArrayList<>();

		@Override
		public boolean sends() {
			for (Response response : responses) {
				if (response.sends()) {
					return true;
				}
			}
			return false;
		}
	}

	interface Task extends Response {
		/**
		 * Will not have an entity manager set in
		 * {@link ThreadLocalAutoCommittingEntityManager}
		 */
		void run();

		@Override
		default boolean sends() {
			return false;
		}
	}

	interface AsyncTask extends Response {
		/**
		 * Will have an entity manager set in
		 * {@link ThreadLocalAutoCommittingEntityManager}
		 */
		void run();

		@Override
		default boolean sends() {
			return false;
		}
	}

	interface CleanUpTask extends Response {
		/**
		 * Will not have an entity manager set in
		 * {@link ThreadLocalAutoCommittingEntityManager}
		 */
		void run();

		@Override
		default boolean sends() {
			return false;
		}
	}

	/**
	 * A special command handler, which will handle any input. It will at most
	 * throw a {@link UserException} if the input is somehow invalid.
	 */
	public interface AnyCommandHandler {
		/**
		 * 
		 * @param command
		 *            the command <i>excluding</i> the leading exclamation mark
		 *            if there was one.
		 * @param apiUser
		 *            the requesting user's api object.
		 * @param userData
		 *            the requesting user's data.
		 * @return null if the command was not handled
		 * @throws UserException
		 *             if the input is invalid
		 */
		@Nonnull
		public Response handle(String command, OsuApiUser apiUser,
				UserData userData) throws UserException,
				IOException, SQLException, InterruptedException;
	}

	/**
	 * 
	 * @param command
	 *            the command <i>excluding</i> the leading exclamation mark if
	 *            there was one.
	 * @param apiUser
	 *            the requesting user's api object.
	 * @param userData
	 *            the requesting user's data.
	 * @return null if the command was not handled
	 * @throws UserException
	 *             if the input is invalid
	 */
	@CheckForNull
	public Response handle(String command, OsuApiUser apiUser,
			UserData userData) throws UserException,
			IOException, SQLException, InterruptedException;

	public default CommandHandler or(CommandHandler next) {
		CommandHandler me = this;
		return new CommandHandler() {
			@Override
			public Response handle(String command, OsuApiUser apiUser,
					UserData userData)
					throws UserException, IOException, SQLException,
					InterruptedException {
				Response response = me.handle(command, apiUser, userData);
				if (response != null) {
					return response;
				}
				return next.handle(command, apiUser, userData);
			}

			@Override
			public String getChoices() {
				return me.getChoices() + "|" + next.getChoices();
			}
		};
	}

	@Nonnull
	public default String getChoices() {
		return "(unknown)";
	}

	/**
	 * Returns a modified {@link CommandHandler}, which calls the underlying
	 * handler only if the incoming message starts with the given string. In
	 * this case, the remaining string is passed to the underlying handler.
	 * 
	 * @param start
	 *            only messages starting with this string are considered. case
	 *            ignored.
	 * @param underlying
	 *            the handler to be called if the message starts with the given
	 *            string. the remaining string will be passed to this handler.
	 * @return the modified handler.
	 */
	public static CommandHandler handling(String start,
			CommandHandler underlying) {
		return new CommandHandler() {
			@Override
			public Response handle(String command, OsuApiUser apiUser,
					UserData userData)
					throws UserException, IOException, SQLException,
					InterruptedException {
				if (!StringUtils.startsWithIgnoreCase(command, start)) {
					return null;
				}
				Response response = underlying.handle(command.substring(start.length()),
						apiUser, userData);
				if (response != null) {
					return response;
				}
				throw new UserException(userData.getLanguage()
						.invalidChoice(command, getChoices()));
			}

			@Override
			public String getChoices() {
				return start
						+ (!underlying.getChoices().isEmpty() ? "("
								+ underlying.getChoices() + ")" : "");
			}
		};
	}

	/**
	 * Returns a modified {@link CommandHandler}, which calls the underlying
	 * handler only if the incoming message starts with the given string. In
	 * this case, the remaining string is passed to the underlying handler. The
	 * underlying handler is assumed to <i>always</i> handle the command.
	 * 
	 * @param start
	 *            only messages starting with this string are considered. case
	 *            ignored.
	 * @param underlying
	 *            the handler to be called if the message starts with the given
	 *            string. the remaining string will be passed to this handler.
	 * @return the modified handler.
	 */
	public static CommandHandler alwaysHandling(String start,
			AnyCommandHandler underlying) {
		return new CommandHandler() {
			@Override
			public Response handle(String command, OsuApiUser apiUser,
					UserData userData)
					throws UserException, IOException, SQLException,
					InterruptedException {
				if (!StringUtils.startsWithIgnoreCase(command, start)) {
					return null;
				}
				return underlying.handle(command.substring(start.length()), apiUser,
						userData);
			}

			@Override
			public String getChoices() {
				return start;
			}
		};
	}

	public abstract static class WithShorthand implements CommandHandler {
		private final String command;
		private final String alias;
		private final String aliasWithSpace;

		public WithShorthand(String command) {
			this.command = command;
			this.alias = String.valueOf(command.charAt(0));
			this.aliasWithSpace = new String(new char[]{command.charAt(0), ' '});
		}

		@Override
		public final Response handle(String originalCommand, OsuApiUser apiUser, UserData userData) throws UserException,
				IOException, SQLException, InterruptedException {
			String lowerCase = originalCommand.toLowerCase();
			if (lowerCase.equals(alias)) {
				return handleArgument("", apiUser, userData);
			}
			if (getLevenshteinDistance(lowerCase, command) <= 2) {
				return handleArgument("", apiUser, userData);
			}
			if (lowerCase.startsWith(aliasWithSpace)) {
				return handleArgument(originalCommand.substring(2), apiUser, userData);
			}
			int pos = lowerCase.indexOf(' ');
			if (pos > 0 && getLevenshteinDistance(lowerCase.substring(0, pos), command) <= 2) {
				return handleArgument(originalCommand.substring(pos + 1), apiUser, userData);
			}
			return null;
		}

		public abstract Response handleArgument(String remaining, OsuApiUser apiUser, UserData userData)
				throws UserException, IOException, SQLException, InterruptedException;
	}
}