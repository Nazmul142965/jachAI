# Profile images

The profile editor accepts PNG and JPEG images after sign-in. The server is the authority:

- request input is limited to 5 MB and 6000x6000 pixels;
- the decoded image is resized to a 256px longest edge;
- output is flattened onto white and encoded as JPEG;
- output above 120 KB is rejected;
- the result is stored in the `user_avatars` MongoDB collection, one document per user;
- `/auth/me` returns the small authenticated data URL, so no public image route or permanent file storage is needed;
- demo accounts cannot change the shared demo profile.

This is appropriate for small avatars, not photo galleries. MongoDB storage remains bounded per user, but Atlas storage monitoring is still required.
