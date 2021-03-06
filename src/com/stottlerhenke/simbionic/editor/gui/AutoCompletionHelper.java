package com.stottlerhenke.simbionic.editor.gui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.stottlerhenke.simbionic.editor.SB_Variable;


/**
 * Helper class to calculate completions for methods and member attributes
 * associated with an object variable.
 * <br>
 * Usage:<br>
 * <ol>
 *  <li> call {@link #initializeContent(Vector, List<String>, List<String>)} to set the 
 *  known variables and class specifications
 *  <li> call {@link #matchPartialDot(Vector, String, String)} to get any
 *  partial match associated with a method/member reference
 *  <li> call {@linkplain #parseDot(String, int, ParseInfo)}} to know if a 
 *  an expression represents a valid method call.
 * </ol>
 * <br>
 * Limitations:<br>
 * <ol>
 *  <li>Only completions for variable methods/members are calculated. For instance,
 *  there will not be completions for something like "myvar.myMethod().x", where the
 *  x refers to the a method that applies to the results of "myvar.myMethod()".
 * </ol>
 * 
 */
public class AutoCompletionHelper {

   /**
    * Helper class to calculate completions for methods and member attributes
    * associated with an object variable.
    * <br>
    * Usage:<br>
    * <ol>
    *  <li> call {@link #initializeContent(Vector, List<String>, List<String>)} to set the 
    *  known variables and class specifications
    *  <li> call {@link #matchPartialDot(Vector, String, String)} to get any
    *  partial match associated with a method/member reference
    *  <li> call {@linkplain #parseDot(String, int, ParseInfo)}} to know if a 
    *  an expression represents a valid method call.
    * </ol>
    * <br>
    * Limitations:<br>
    * <ol>
    *  <li>Only completions for variable methods/members are calculated. For instance,
    *  there will not be completions for something like "myvar.myMethod().x", where the
    *  x refers to the a method that applies to the results of "myvar.myMethod()".
    * </ol>
    *
    */
   public AutoCompletionHelper() {
   }
   
   /** clears the variables and class specifications previously set by 
    * {@link #initializeContent(Vector, List<String>, List<String>)} **/
   public void clearContent() {
     variablesMap.clear();
     clearKnownClassNames();
   }

    /**
     * defines the variables and class specifications the helper knows about.
     * This data is used when calling {@link #parseDot(String, int, ParseInfo)}
     * and {@link #matchPartialDot(Vector, String, String)}
     * 
     * @param variables
     * @param importedClasses
     */
    public void initializeContent(List<SB_Variable> variables,
            List<String> importedClasses) {
        clearContent();
        if (variables != null) {
            for (SB_Variable var : variables) {
                variablesMap.put(var.getName(), var);
            }
        }

        if (importedClasses != null) {
            initFromFullClassNames(importedClasses);
        }
    }

    /**
     * cache the name of all classes the helper knows about. These classes are
     * derived from {@link #importedPackages} and {@link #importedClasses}.
     * These classes are used by {@link #parseDot(String, int, ParseInfo)} to
     * include classes along with variable when performing autocompletion.
     */
    private void initFromFullClassNames(List<String> importedClasses) {
        for (String importedClass : importedClasses) {
            addClassName(importedClass);
        }
    }

    /**
     * Given a String that is a fully qualified Class name, guesses the simple
     * name and registers the two names in the maps maintained by this
     * instance.
     * */
    private void addClassName(String fullClassName) {
        String shortname = inferSimpleClassName(fullClassName);

        classNameToSimpleName.put(fullClassName, shortname);

        simpleNameToClassNames.computeIfAbsent(shortname,
                s -> new HashSet<>());
        simpleNameToClassNames.get(shortname).add(fullClassName);
    }

    private void clearKnownClassNames() {
        classNameToSimpleName.clear();
        simpleNameToClassNames.clear();
    }

    /**
     * Generate completions for "variableName.dotArg".&nbsp;Returns true if some
     * completions where added to matchList.
     * 
     * 
     * @param matchList
     *            - new completions will be added to this list
     * @param variableName
     * @param dotArg
     * @return
     */
    public boolean matchPartialDot(List<SB_Auto_Match> matchList,
            String variableName, String dotArg) {
        try {
            SB_Variable var = getVariable(variableName);

            if (var == null) {
                return matchPartialDotStaticMethod(matchList, variableName,
                        dotArg);
            }

            // access the list of possible methods for the given variable
            String type = var.getFullTypeName();

            if (type == null) {
                return false;
            }

            Class<?> varClass = null;
            try {
                varClass = Class.forName(type);
            } catch (Exception e) {
                e.printStackTrace();
                return false; // no match
            }

            if (varClass == null) {
                return false;
            }

            Field[] members = varClass.getFields();// only the public fields
                                                   // //fixme. Does this include
                                                   // superclass members?
            Method[] methods = varClass.getMethods();// public methods

            Stream<Field> memberStream = Arrays.asList(members).stream();

            Stream<Method> methodStream = Arrays.asList(methods).stream();

            return addMatchesToMatchList(matchList, memberStream, methodStream,
                    dotArg);

        } catch (Exception e) {
            // e.printStackTrace();
        }

        return false;
    }

    /**
     * 2018-05-07 -jmm
     * <br>
     * XXX: The current handling for ambiguous class names does not help
     * disambiguate between methods on that class. The current implementation
     * will show matches for all methods and fields in both classes.
     * @param matchList
     *            The list of matches where matching methods should be added.
     * @param className A simple name of a class/classes.
     * @param dotArg
     * @return
     */
    public boolean matchPartialDotStaticMethod(
            List<SB_Auto_Match> matchList, String className,
            String dotArg) {
        try {
            Set<Class<?>> varClasses = this.getClasses(className);

            if (varClasses.isEmpty()) {
                return false;
            }

            boolean completionsAdded = false;

            for (Class<?> varClass : varClasses) {
                Field[] members = varClass.getFields();// only the public fields
                // //fixme. Does this include
                // superclass members?
                Method[] methods = varClass.getMethods();// public methods

                Stream<Field> staticMemberStream = Arrays.asList(members)
                        .stream().filter(field -> Modifier
                                .isStatic(field.getModifiers()));

                Stream<Method> staticMethodStream = Arrays.asList(methods)
                        .stream().filter(method -> Modifier
                                .isStatic(method.getModifiers()));

                boolean addedInThisLoop = addMatchesToMatchList(
                        matchList, staticMemberStream, staticMethodStream,
                        dotArg);

                completionsAdded = completionsAdded | addedInThisLoop;
            }

            return completionsAdded;

        } catch (Exception e) {
            //2018-05 TODO: Why are exceptions swallowed at this step?
            // e.printStackTrace();
        }

        return false;
    }

   /**
    * returns variable named varName.&nbsb;Returns null is not such variable exists
    * @param varName
    * @return
    */
   private SB_Variable getVariable(String varName) {
      return variablesMap.get(varName);
   }
   
   /**
    * returns info.index == -1 if not dot completion might be possible. Otherwise, 
    * info.funcName is the name of the variable on which the dot is being applied, and
    * info.paramName is the string after the dot.
    * 
    * Limitations:<br>
    * <ol>
    *  <li>Only completions for variable methods/members are calculated. For instance,
    *  there will not be completions for something like "myvar.myMethod().x", where the
    *  x refers to the a method that applies to the results of "myvar.myMethod()".
    * </ol>
    * @param expr expression being parsed
    * @param pos index in the expression at which the completion should be done 
    * @param info returned object
    */
   
   public void parseDot(String expr, int pos, ParseInfo info) {   
      //find the candidate for a variable, which is the string
      //before a dot. First find the dot going backward from the current
      //position
      String delimitersChars = "\n\"(), &!=<>|-+/";
      info.index = -1; //no dot completion possible
      int dotPosition = -1;
      for (int i = pos-1 ; i >= 0 ; i--) {
         char c =  expr.charAt(i);
         if (delimitersChars.indexOf(c) != -1) {
            return; //there is a dot invocation
         }
         
         if (c=='.') {
            dotPosition =i;
            break;
         }
      }
      
      if (dotPosition ==-1) {
         return; //no dot completion possible
      }
      
      //now try to find the variable name going backwards from the dotPosition
      //until the beginning of the expression of until finding a delimiter
      
      delimitersChars +="."; //add the dot a new delimiter
      int varNameStartPosition=0;//beginning of the expression
      for (int i= dotPosition-1 ; i >=0 ; i--) {
         char c = expr.charAt(i);
         if (delimitersChars.indexOf(c)!=-1) {
            varNameStartPosition=i+1;
            break;
         }
      }
      
      //else
      String varName = expr.substring(varNameStartPosition,dotPosition);
      
      //check we have a variable and not some garbage string
      if (getVariable(varName)==null) {
         //see if there is a class we could load
         if (getClasses(varName).isEmpty()) {
            return;//there was not variable before the dot
         }
      }
      String methodName = expr.substring(dotPosition+1,pos);
      info.index = dotPosition;
      info.funcName = varName;
      info.paramName = methodName;
      
   }

    /**
     * if a known java class starts with classNamePrefix add the simple class
     * name to matchList
     * 
     * @param matchList
     *            The list of matches where matching classes should be added.
     * @param classNamePrefix
     */
    public void matchPartialClassName(
            List<SB_Auto_Match> matchList,
            String classNamePrefix) {
        if (classNamePrefix == null || classNamePrefix.length() == 0)
            return;

        //XXX: Old approach was accidentally resistant to CMEs; the new
        //approach is not.
        Optional<Entry<String, Set<String>>> shortNameAndFullNames
        = simpleNameToClassNames.entrySet().stream()
            .filter(e -> classNamePrefix.regionMatches(0,
                    e.getKey(), 0, classNamePrefix.length()))
            .findAny();

        shortNameAndFullNames.ifPresent(shortAndFull -> {
            String knownSimpleName = shortAndFull.getKey();

            Set<String> fullNamesForShortName
            = shortAndFull.getValue();

            for (String fullName : fullNamesForShortName) {
                //Display qualified names if there is ambiguity.
                //Note that resolving this ambiguity does not actually
                //help with method autocompletion.
                //XXX: "Canonical" representation of Java classes are not used.
                if (fullNamesForShortName.size() > 1) {
                    matchList.add(SB_Auto_Match.ofCanonicalDisplay(fullName,
                            knownSimpleName));
                } else {
                    matchList.add(SB_Auto_Match.ofSame(knownSimpleName));
                }
            }
        });

    }

    /**
     * 2018-05-07 -jmm <br>
     * XXX: The older versions of this method did not handle collisions between
     * different classes with the same simple name.
     * <br>
     * 
     * @param simpleName A simple class name (no packages included)
     * @return A set of known classes that have equivalent simple names.
     */
    private final Set<Class<?>> getClasses(String simpleName) {
        // try one of the imported classes
        Optional<Set<String>> maybeClassNames
                = Optional.ofNullable(simpleNameToClassNames.get(simpleName));

        Set<String> classNames = maybeClassNames.orElse(new HashSet<>());

        return classNames.stream()
                .flatMap(className -> {
                    // Attempts to get the class with name className; failures
                    // are ignored.
                    try {
                        return Stream.of(Class.forName(className));
                    } catch (ClassNotFoundException e) {
                        return Stream.of();
                    }
                })
                .collect(Collectors.toSet());
    }

    /**
     * @param simpleName A String that is a simple class name.
     * @return A set containing the full names of all known classes with the
     * specified simple name.
     * */
    private final Set<String> getFullNames(String simpleName) {
        return simpleNameToClassNames.getOrDefault(simpleName,
                new HashSet<>());
    }

    /**
     * This method will attempt to shorten (assumed) class name {@code
     * fullName} to an unqualified simple name if no simple name collisions are
     * detected with other known classes.
     * @return 
     * */
    protected final String getUnambiguousName(String fullName) {
        String simpleName = inferSimpleClassName(fullName);
        if (getFullNames(simpleName).size() > 1) {
            return fullName;
        } else {
            return simpleName;
        }
    }

    /**
     * Given what might be a fully-qualified class name, return the shorter
     * "simple name" (that would be returned by {@link Class#getSimpleName()})
     * produced by removing the package information. This is assumed to be
     * everything before the last {@code .} character of {@code fullClassName}.
     * */
    private static String inferSimpleClassName(String fullClassName) {
        int i = fullClassName.lastIndexOf('.');

        return (i >= 0) ? fullClassName.substring(i+1)
                        : fullClassName;
    }

    /**
     * 
     * @return true iff any matches have been added to {@code matchList}. Note
     * that the matches might have been already present.
     * */
    private boolean addMatchesToMatchList(
            List<SB_Auto_Match> matchList,
            Stream<Field> fieldsToSearch, Stream<Method> methodsToSearch,
            String dotArg) {
        List<SB_Auto_Match> matchedMembers = fieldsToSearch
                .filter(field -> field.getName().startsWith(dotArg))
                .map(field -> getFieldIAD(field))
                .sorted(MATCH_COMPARATOR)
                .collect(Collectors.toList());

        List<SB_Auto_Match> matchedMethods = methodsToSearch
                .filter(method -> method.getName().startsWith(dotArg))
                .map(method -> getMethodIAD(method))
                .sorted(MATCH_COMPARATOR)
                .collect(Collectors.toList());

        matchList.addAll(matchedMembers);
        matchList.addAll(matchedMethods);

        boolean noAddedCompletions
                = matchedMembers.isEmpty() && matchedMethods.isEmpty();

        return !noAddedCompletions;
    }

    private SB_Auto_Match getFieldIAD(Field field) {
        String fieldName = field.getName();
        String canonicalFieldType = field.getType().getName();
        String fieldType = getHTMLEscapedClassName(field.getType());
        String display = fieldName + " : " + fieldType;
        String canonical = fieldName + " : " + canonicalFieldType;
        return SB_Auto_Match.of(canonical, fieldName, display);
    }

    private SB_Auto_Match getMethodIAD(Method method) {
        return SB_Auto_Match.ofSame(getMethodDisplayName(method));
    }

    /**
     * returns string to display in the autocompletion list.&nbsp;Methods are
     * displayed as "methodName(type_1,...,type_n)"
     * 
     * @param method
     * @return
     */
    private String getMethodDisplayName(Method method) {
        String str = method.getName() + "(";
        Class<?>[] params = method.getParameterTypes();
        // usually the params name is null

        if (params != null) {
            List<String> classStrings = Arrays.asList(params).stream()
                    .map(param -> getHTMLEscapedClassName(param))
                    .collect(Collectors.toList());

            String paramNamesString = String.join(",", classStrings);
            str += paramNamesString;
        }

        str += ")";

        return str;
    }

    /**
     * Escapes {@code '[', ']'} characters in the class name if {@code c} is an
     * array. Otherwise identical to {@link #getUnambiguousClassName(Class)}
     */
    private final String getHTMLEscapedClassName(Class<?> c) {
        String name = getUnambiguousClassName(c);
        if (c.isArray()) {
            return name.replaceAll("\\[", " &#91;")
                    .replaceAll("\\]", " &#93;");
        }
        return name;
    }

    /**
     * Returns either the simple name of the class (if no other known class has
     * the same simple name) or the full name of the class (if another known
     * class has the same simple name). Note that this errs towards shortening
     * names in the event that the class name tracking done by {@link
     * #classNameToSimpleName} and {@link #simpleNameToClassNames} is out of
     * sync with the project environment.
     * 
     * @param c
     * @return
     */
    private final String getUnambiguousClassName(Class<?> c) {
        String simpleName = c.getSimpleName();
        int knownWithSimpleName = Optional.ofNullable(
                simpleNameToClassNames.get(simpleName))
                .map(set -> set.size())
                .orElse(1);
        return (knownWithSimpleName > 1) ? c.getName()
                                         : simpleName;
    }

   /** map SB_Variable name to variableObject for those variables
    * provided in {@link #initializeContent(Vector, List<String>, List<String>)}**/
   private Map<String, SB_Variable> variablesMap = new HashMap<String,SB_Variable>();

    /**
     * A mapping between class names ("fully qualified" with packages, e.g.
     * {@code java.lang.String}) and their corresponding simple names (e.g.
     * {@code String}); note that many class names may be mapped to the same
     * simple name.
     * */
    private Map<String, String> classNameToSimpleName = new HashMap<>();

    /**
     * A mapping between simple names for classes and all class names that are
     * shortened to that simple name. In essence, an "inversion" of
     * {@link #classNameToSimpleName}. Null values are not expected in this
     * map.
     * <br>
     * Calls to {@link #matchPartialClassName(List, String)
     * matchPartialClassName} must be synchronized with calls to
     * {@link #clearKnownClassNames()} (and therefore {@link #clearContent()})
     * and {@link #addClassName(String) addClassName} (and therefore {@link
     * #initializeContent(List, List) initializeContent}) in order to avoid
     * a CME on this collection.
     * */
    private Map<String, Set<String>> simpleNameToClassNames = new HashMap<>();

   /**
    * Helper class containing information about a match for an expression.
    * This class used to be declared inside {@link AutoCompletionTextField}. 
    *
    * @author Owner
    *
    */
   public static class ParseInfo{
      public String funcName;
      public String paramName;
      public int index;
      public int paren; //parenthesis?
      public int first;

      public String toString(){
         return "funcName = " + funcName + "; paramName = " + paramName + "; index = " + index + "; paren = " + paren;
      }
   }

   /**
    * Helper class that associates the string shown in autocomplete with the
    * string that should be inserted into the edited area by autocomplete.
    * <br>
    * Previously named InsertionAndDisplayStrings; the shorter name is more
    * general but may be misleading.
    * <br>
    * TODO: It may be appropriate to have a more general "match object" with
    * customizable "rendering" to produce the insertion and display strings.
    * */
   protected static final class SB_Auto_Match {

       /**
        * The string that autocomplete should insert into the edited text
        * component.
        * */
       private final String insertion;
       /**
        * The string that autocomplete should display in the autocomplete
        * SB_GlassPane.
        * */
       private final String display;

       /**
        * 2018-05-07 -jmm
        * <br>
        * The "canonical" representation of the match. This currently appears
        * to be the display string (!Complete with HTML escaping and
        * typesetting!) with fully qualified type name annotations.
        * */
       private final String canonical;

       SB_Auto_Match(String canonical, String insertion, String display) {
           this.canonical = Objects.requireNonNull(canonical);
           this.insertion = Objects.requireNonNull(insertion);
           this.display = Objects.requireNonNull(display);
       }

       public String getStringToInsert() {
           return insertion;
       }

       /**
        * @return A string representing the match with fully-qualified type
        * annotations. (e.g. {@code java.lang.String} instead of {@code
        * String}) In a sense, this might be the "canonical" representation
        * of a match object.
        * */
       public String getFullyQualifiedAnnotations() {
           return canonical;
       }

       public String getDisplay() {
           return display;
       }

       /***/
       public static SB_Auto_Match of(String canonical, String insertion,
               String display) {
           return new SB_Auto_Match(canonical, insertion, display);
       }

       public static SB_Auto_Match ofCanonicalDisplay(String canonical,
               String insertion) {
           return new SB_Auto_Match(canonical, insertion, canonical);
       }

       /**
        * This convenience method simplifies the case where the string to be
        * inserted is identical to the string to be displayed.
        * */
       public static SB_Auto_Match ofSame(String canonical) {
           return new SB_Auto_Match(canonical, canonical, canonical);
       }

   }

    protected static final Comparator<SB_Auto_Match> MATCH_COMPARATOR
            = (match1, match2) -> match1.getDisplay().compareTo(
                    match2.getDisplay());

}
