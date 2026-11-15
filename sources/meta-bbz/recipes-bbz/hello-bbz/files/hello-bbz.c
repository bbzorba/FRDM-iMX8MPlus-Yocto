#include <stdio.h>
#include <sys/utsname.h>

int main(void)
{
	struct utsname u;

	if (uname(&u) == 0)
		printf("Hello from meta-bbz on %s (%s, kernel %s)\n",
		       u.nodename, u.machine, u.release);
	return 0;
}
