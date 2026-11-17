# Fixes to third-party code that warns in our builds, which its owners haven't shipped yet.
#
# The submodules stay as they are. A fix is made to a copy in the build folder, and says exactly the
# text it replaces: if an update moves that text, or says it twice, the configure stops here, so the
# fix is looked at again, and dropped once upstream has it.

# crosstune_patch(<source file> <copy> <old text> <new text> <change>): writes <copy> from <source file>,
# with <old text> replaced by <new text>, and a first line that says the file was changed, when and how
# (<change>, "YYYY-MM-DD: what"), as the LGPL asks of a changed file. It rewrites the copy only when it
# changed, so a configure that changes nothing doesn't compile everything again.
function(crosstune_patch source copy old new change)
    if(NOT change MATCHES "^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]: ")
        message(FATAL_ERROR "The change to ${source} needs its date first: \"YYYY-MM-DD: what\"")
    endif()
    file(READ "${source}" content)
    string(FIND "${content}" "${old}" first)
    string(FIND "${content}" "${old}" last REVERSE)
    if(first EQUAL -1 OR NOT first EQUAL last)
        message(FATAL_ERROR "${source} no longer has \"${old}\" exactly once: look at what the update did, and drop or redo the fix in app/src/main/cpp/")
    endif()
    string(REPLACE "${old}" "${new}" patched "${content}")
    set(patched "// Changed by Crosstune (https://github.com/astrovm/crosstune) on ${change}\n${patched}")
    set(existing "")
    if(EXISTS "${copy}")
        file(READ "${copy}" existing)
    endif()
    if(NOT existing STREQUAL patched)
        get_filename_component(folder "${copy}" DIRECTORY)
        file(MAKE_DIRECTORY "${folder}")
        file(WRITE "${copy}" "${patched}")
    endif()
    # The copy is made again when the original changes.
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${source}")
endfunction()

# crosstune_copy_tree(<source folder> <copy folder> <patched file>...): the folder in the build folder,
# without its top-level docs and tests, which aren't built, nor Git's files, and without the files
# <patched file>... (paths in the folder), for crosstune_patch to write. Files that haven't changed
# keep their time, so they aren't compiled again.
function(crosstune_copy_tree source copy)
    set(leave_out)
    foreach(file ${ARGN})
        list(APPEND leave_out REGEX "^${source}/${file}$" EXCLUDE)
    endforeach()
    file(COPY "${source}/" DESTINATION "${copy}"
         PATTERN ".git" EXCLUDE
         REGEX "^${source}/(\\.github|docs|tests|background\\.png)(/|$)" EXCLUDE
         ${leave_out})
endfunction()
