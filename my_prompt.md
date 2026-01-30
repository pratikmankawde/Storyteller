Read the instruction file at `FullFeatured app Instructions.md` and create a tasklist.json file with each tasks, task and sub-tasks (each with description, ai generated work-items), milestores mentioned in the instruction file. Add a done(true/false) attribute to each task and sub tasks. 
Then go through each task and implement it. Set the done flag to true when finished. Write a test activity for all the tasks that can be tested.

Iterate over the task list to finish all the tasks and achieve all the milestone.

Use placeholders stubs for ai model files.

Create usertask.md file to document all the things you need from me.
Do not wait for user input, try to finish all the tasks. Skip a task that cannot be finished without user input.

After each task is finished, build and install on the emulator. Run the corresponding test activity to test the feature. If there are any errors or issues, fix them and repeat. If you encounter file lock issues when building, kill all the OpenJDK processes and try clean build again. If that fails, relaunch Cursor IDE and try a clean build. If you encounter server error or connection issues, keep retrying. If after retrying for 15mins things still don't work, hibernet the computer.

Once all the tasks are finished(or only few left which cannot be finished without user input or unfixable errors), hibernet the computer.






The character extraction is not working well. Follow this workflow using Gwen LLM(generate appropriate prompts to extract this information or use the prompts suggested in 'C:\Users\Pratik\source\Storyteller\FullFeatured app Instructions.md'):
1. Scan PDF text to detect start of first Chapter. Simple technique of detecting a chapter could be: it will start from a new page, it starts with the chapter name, title.
2. Start the scan of the PDF from the start of the first chapter to find the start of the second chapter. The page before start of the second chapter is the end of the first chapter.
3. You can find start and end of chapters using by following this process till the end of the novel. Store the start and end pages of each chapter.
4. Now do this for first chapter:
    * Use Gwen to detect Actors/Characters in the chapter by passing it text of one page at a time, and asking if it found a character in that page.
    * For all the found characters,
        * If the character was already in the database, move to next character.
        * Else ask Gwen to infer the personality traits of that character.
    * Create a list of these characters, with their personality traits.
    * Ask Gwen to suggest a voice profile for each of the found characters given their personality types. The output of this query should be in the format of a json, that can be passed to TTS Model.
    * Use the Gwen's output to assign a speaker to each character.
    * Store this information in database. The analysis will be keyed with book id.
* Display the characters found and their assigned speakers on a page.
    * User should be able to listen an example of the assigned voice. Then optionally change the speaker from a list of similar speakers available in the TTS model.
* On the reading page, show the contents of the first chapter's, first page.
* When the reading of this page is done, show content of next page.
* When reading of first chapter is finish, perform the same analysis for second chapter(as described above).
* This continues as reading of a chapter is finished.
* If user moves to a certain chapter directly, then presses the 'Read' button, start processing of that chapter in the background and show wait/loading. Start reading when the analysis is done and voices are available.
* Populate Character Analysis and Insights pages per chapter by quering Gwen about it.