package org.basex.core;

import java.util.*;
import java.util.regex.*;

import org.basex.io.*;
import org.basex.util.*;
import org.basex.util.list.*;

/**
 * Provides central access to all databases and backups.
 *
 * @author BaseX Team 2005-19, BSD License
 * @author Jens Erat
 */
public final class Databases {
  /** Date pattern. */
  public static final String DATE = "\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}";

  /** Allowed characters for database names (additional to letters and digits).
   * The following characters are invalid:
   * <ul>
   *   <li> {@code ,?*}" are used by the glob syntax</li>
   *   <li> {@code ;} is reserved for separating commands.</li>
   *   <li> {@code :*?\"<>\/|}" are used for filenames and paths</li>
   * </ul>
   */
  public static final String DBCHARS = "-+=~!#$%^&()[]{}@'`";

  /** Regex representation of allowed database characters. */
  private static final String REGEXCHARS = DBCHARS.replaceAll("(.)", "\\\\$1");
  /** Pattern to extract the database name from a backup file name. */
  private static final Pattern ZIPPATTERN = Pattern.compile('-' + DATE + '\\' + IO.ZIPSUFFIX + '$');
  /** Regex indicator. */
  private static final Pattern REGEX = Pattern.compile(".*[*?,].*");

  /** Static options. */
  private final StaticOptions soptions;

  /**
   * Creates a new instance and loads available databases.
   * @param soptions static options
   */
  Databases(final StaticOptions soptions) {
    this.soptions = soptions;
  }

  /**
   * Lists all available databases and backups.
   * @return database and backup list
   */
  public StringList list() {
    return list(true, null);
  }

  /**
   * Lists all available databases matching the given name. Supports glob patterns.
   * @param name database pattern (may be {@code null})
   * @return database list
   */
  public StringList listDBs(final String name) {
    return list(false, name);
  }

  /**
   * Returns the sorted names of all available databases and, optionally, backups.
   * Filters for {@code name} if not {@code null} with glob support.
   * @param backup return backups?
   * @param name database pattern (may be {@code null})
   * @return database and backups list
   */
  private StringList list(final boolean backup, final String name) {
    final Pattern pt = name == null ? null : regex(name);
    final IOFile[] children = soptions.dbPath().children();
    final StringList list = new StringList(children.length);
    final HashSet<String> map = new HashSet<>(children.length);
    for(final IOFile f : children) {
      final String fn = f.name();
      String add = null;
      if(backup && fn.endsWith(IO.ZIPSUFFIX)) {
        final String nn = ZIPPATTERN.split(fn)[0];
        if(!nn.equals(fn)) add = nn;
      } else if(f.isDir() && !fn.startsWith(".")) {
        add = fn;
      }
      // add entry if it matches the pattern, and has not already been added
      if(add != null && (pt == null || pt.matcher(add).matches()) && map.add(add)) {
        list.add(add);
      }
    }
    return list.sort(false);
  }

  /**
   * Returns a regular expression for the specified name pattern.
   * @param pattern pattern
   * @return regular expression
   */
  public static Pattern regex(final String pattern) {
    return regex(pattern, "");
  }

  /**
   * Returns a regular expression for the specified name pattern.
   * @param pattern pattern (can be {@code null})
   * @param suffix regular expression suffix
   * @return regular expression or {@code null}
   */
  public static Pattern regex(final String pattern, final String suffix) {
    if(pattern == null) return null;
    final String nm = REGEX.matcher(pattern).matches() ? IOFile.regex(pattern) :
      pattern.replaceAll("([" + REGEXCHARS + "])", "\\\\$1") + suffix;
    return Pattern.compile(nm, Prop.CASE ? 0 : Pattern.CASE_INSENSITIVE);
  }

  /**
   * Returns the names of all backups.
   * @return backups
   */
  public StringList backups() {
    final StringList backups = new StringList();
    for(final IOFile f : soptions.dbPath().children()) {
      final String n = f.name();
      if(n.endsWith(IO.ZIPSUFFIX)) backups.add(n.substring(0, n.lastIndexOf('.')));
    }
    return backups;
  }

  /**
   * Returns the name of a specific backup, or all backups found for a specific database,
   * in a descending order.
   * @param db database
   * @return names of specified backups
   */
  public StringList backups(final String db) {
    final StringList backups = new StringList();
    final IOFile file = soptions.dbPath(db + IO.ZIPSUFFIX);
    if(file.exists()) {
      backups.add(db);
    } else {
      final Pattern regex = regex(db, '-' + DATE + '\\' + IO.ZIPSUFFIX);
      for(final IOFile f : soptions.dbPath().children()) {
        final String n = f.name();
        if(regex.matcher(n).matches()) backups.add(n.substring(0, n.lastIndexOf('.')));
      }
    }
    return backups.sort(Prop.CASE, false);
  }

  /**
   * Extracts the name of a database from the name of a backup.
   * @param backup Name of the backup file. Valid formats:
   *   {@code [dbname]-yyyy-mm-dd-hh-mm-ss},
   *   {@code [dbname]}
   * @return name of the database ({@code [dbname]})
   */
  public static String name(final String backup) {
    return Pattern.compile('-' + DATE + '$').split(backup)[0];
  }

  /**
   * Extracts the date of a database from the name of a backup.
   * @param backup name of the backup file, including the date
   * @return date string
   */
  public static Date date(final String backup) {
    return DateTime.parse(backup.replaceAll("^.+-(" + DATE + ")$", "$1"));
  }

  /**
   * Checks if the specified character is a valid character for a database name.
   * @param ch the character to be checked
   * @param firstLast character is first or last
   * @return result of check
   */
  public static boolean validChar(final int ch, final boolean firstLast) {
    return Token.letterOrDigit(ch) || DBCHARS.indexOf(ch) != -1 || !firstLast && ch == '.';
  }

  /**
   * Checks if the specified string is a valid database name.
   * @param name name to be checked (can be {@code null})
   * @return result of check
   */
  public static boolean validName(final String name) {
    return validName(name, false);
  }

  /**
   * Checks if the specified string is a valid database name.
   * @param name name to be checked (can be {@code null})
   * @param glob allow glob syntax
   * @return result of check
   */
  public static boolean validName(final String name, final boolean glob) {
    if(name == null) return false;
    final int nl = name.length();
    for(int n = 0; n < nl; n++) {
      final char ch = name.charAt(n);
      if((!glob || ch != '?' && ch != '*' && ch != ',') && !validChar(ch, n == 0 || n + 1 == nl))
        return false;
    }
    return nl != 0;
  }
}
